(ns clj-jargon.permissions
  (:use [clj-jargon.validations]
        [clj-jargon.gen-query]
        [clj-jargon.users]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure-commons.file-utils :as ft]
            [clj-jargon.lazy-listings :as ll]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clj-jargon.item-info :as item]
            [medley.core :refer [take-upto]])
  (:import [org.irods.jargon.core.protovalues FilePermissionEnum]
           [org.irods.jargon.core.pub IRODSFileSystemAO
                                      DataObjectAO
                                      CollectionAO
                                      CollectionAndDataObjectListAndSearchAO]
           [org.irods.jargon.core.pub.domain UserFilePermission]
           [org.irods.jargon.core.pub.io IRODSFile]
           [org.irods.jargon.core.query IRODSQueryResultRow
                                        RodsGenQueryEnum
                                        CollectionAndDataObjectListingEntry]))

(declare permissions)

(def read-perm FilePermissionEnum/READ)
(def write-perm FilePermissionEnum/WRITE)
(def own-perm FilePermissionEnum/OWN)
(def none-perm FilePermissionEnum/NONE)

(defn- max-perm
  "It determines the highest permission in a set of permissions."
  [perms]
  (cond
     (contains? perms own-perm)   own-perm
     (contains? perms write-perm) write-perm
     (contains? perms read-perm)  read-perm
     :else                        none-perm))

(defmulti fmt-perm
  "Translates a jargonesque permission to a keyword permission.

   Parameter:
     jargon-perm - the jargon permission

   Returns:
     The keyword representation, :own, :write, :read or nil."
  type)
(defmethod fmt-perm FilePermissionEnum
  [jargon-perm]
  (condp = jargon-perm
    own-perm   :own
    write-perm :write
    read-perm  :read
               nil))
(defmethod fmt-perm Long
  [^Long access-type-id]
  (fmt-perm (FilePermissionEnum/valueOf access-type-id)))
(defmethod fmt-perm String
  [^String access-type-id]
  (fmt-perm (Long/parseLong access-type-id)))

(defn- log-last
  [item]
  (log/warn "process-perms: " item)
  item)

(def perm-order-map
  (into {} (map vector [read-perm write-perm own-perm] (range))))

(defn str->perm-const
  [s]
  (FilePermissionEnum/valueOf (Integer/parseInt s)))

(defn collection-perms-rs
  [cm user coll-path]
  (execute-gen-query
   cm
   "select %s where %s = '%s' and %s = '%s'"
   [RodsGenQueryEnum/COL_COLL_ACCESS_TYPE
    RodsGenQueryEnum/COL_COLL_NAME
    coll-path
    RodsGenQueryEnum/COL_COLL_ACCESS_USER_NAME
    user]))

(defn dataobject-perms-rs
  [cm user-id data-path]
  (execute-gen-query
   cm
   "select %s where %s = '%s' and %s = '%s' and %s = '%s'"
   [RodsGenQueryEnum/COL_DATA_ACCESS_TYPE
    RodsGenQueryEnum/COL_COLL_NAME
    (ft/dirname data-path)
    RodsGenQueryEnum/COL_DATA_NAME
    (ft/basename data-path)
    RodsGenQueryEnum/COL_DATA_ACCESS_USER_ID
    user-id]))

(defn perm-map-for
  [perms-code]
  (let [perms (FilePermissionEnum/valueOf (Integer/parseInt perms-code))]
    {:read  (contains? #{read-perm write-perm own-perm} perms)
     :write (contains? #{write-perm own-perm} perms)
     :own   (contains? #{own-perm} perms)}))

(defn- mcat
  "Reimplementation of mapcat that doesn't break laziness. See http://clojurian.blogspot.com/2012/11/beware-of-mapcat.html"
  [f coll]
  (lazy-seq (if (not-empty coll) (concat (f (first coll)) (mcat f (rest coll))))))

(defn- user-collection-perms*
  [cm user coll-path escape-hatch]
  (validate-path-lengths coll-path)
  (->> (concat [user] (lazy-seq (user-groups cm user)))
       (mcat #(collection-perms-rs cm % coll-path))
       (map (fn [^IRODSQueryResultRow rs] (.getColumnsAsList rs)))
       (map #(FilePermissionEnum/valueOf (Integer/parseInt (first %))))
       (take-upto escape-hatch)
       (doall)))

(defn- user-collection-perms
  [cm user coll-path]
  (validate-path-lengths coll-path)
  (set (user-collection-perms* cm user coll-path (constantly false))))

(defn- user-dataobject-perms*
  [cm user data-path escape-hatch]
  (validate-path-lengths data-path)
  (->> (concat [(username->id cm user)] (lazy-seq (user-group-ids cm user)))
       (mcat #(dataobject-perms-rs cm % data-path))
       (map (fn [^IRODSQueryResultRow rs] (.getColumnsAsList rs)))
       (map #(FilePermissionEnum/valueOf (Integer/parseInt (first %))))
       (take-upto escape-hatch)
       (doall)))

(defn- user-dataobject-perms
  [cm user data-path]
  (validate-path-lengths data-path)
  (set (user-dataobject-perms* cm user data-path (constantly false))))

(defn dataobject-perm-map
  "Uses (user-dataobject-perms) to grab the 'raw' permissions for
   the user for the dataobject at data-path, and returns a map with
   the keys :read :write and :own. The values are booleans."
  [cm user data-path]
  (validate-path-lengths data-path)
  (let [perms  (user-dataobject-perms cm user data-path)
        read   (or (contains? perms read-perm)
                   (contains? perms write-perm)
                   (contains? perms own-perm))
        write  (or (contains? perms write-perm)
                   (contains? perms own-perm))
        own    (contains? perms own-perm)]
    {:read  read
     :write write
     :own   own}))

(defn collection-perm-map
  "Uses (user-collection-perms) to grab the 'raw' permissions for
   the user for the collection at coll-path and returns a map with
   the keys :read, :write, and :own. The values are booleans."
  [cm user coll-path]
  (validate-path-lengths coll-path)
  (let [perms  (user-collection-perms cm user coll-path)
        read   (or (contains? perms read-perm)
                   (contains? perms write-perm)
                   (contains? perms own-perm))
        write  (or (contains? perms write-perm)
                   (contains? perms own-perm))
        own    (contains? perms own-perm)]
    {:read  read
     :write write
     :own   own}))

(defn- user-id-dataobject-perm?
  "Utility function which checks if a given user-id has a given permission on
  data-path"
  [cm user-id data-path perm]
  (let [results (dataobject-perms-rs cm user-id data-path)]
    (some #(get (perm-map-for (first (.getColumnsAsList ^IRODSQueryResultRow %))) perm) results)))

(defn- dataobject-perm?
  "Utility function that checks to see of the user has the specified
   permission or better for data-path."
  [cm username data-path checked-perm]
  (validate-path-lengths data-path)
  (or (user-id-dataobject-perm? cm (username->id cm username) data-path checked-perm)
      (some #(user-id-dataobject-perm? cm % data-path checked-perm) (user-group-ids cm username))))

(defn- dataobject-readable?
  "Checks to see if the user has read permissions on data-path. Only
   works for dataobjects."
  [cm user data-path]
  (validate-path-lengths data-path)
  (dataobject-perm? cm user data-path :read))

(defn- dataobject-writeable?
  "Checks to see if the user has write permissions on data-path. Only
   works for dataobjects."
  [cm user data-path]
  (validate-path-lengths data-path)
  (dataobject-perm? cm user data-path :write))

(defn- owns-dataobject?
  "Checks to see if the user has ownership permissions on data-path. Only
   works for dataobjects."
  [cm user data-path]
  (validate-path-lengths data-path)
  (dataobject-perm? cm user data-path :own))

(defn- user-coll-perm?
  "Utility function which checks if a given user has a given permission on
  coll-path"
  [cm user coll-path perm]
  (let [results (collection-perms-rs cm user coll-path)]
    (some #(get (perm-map-for (first (.getColumnsAsList ^IRODSQueryResultRow %))) perm) results)))

(defn- collection-perm?
  "Utility function that checks to see if the user has the specified
   permission or better for the collection path."
  [cm username coll-path checked-perm]
  (validate-path-lengths coll-path)
  (or (user-coll-perm? cm username coll-path checked-perm)
      (some #(user-coll-perm? cm % coll-path checked-perm) (user-groups cm username))))

(defn- collection-readable?
  "Checks to see if the user has read permissions on coll-path. Only
   works for collection paths."
  [cm user coll-path]
  (validate-path-lengths coll-path)
  (collection-perm? cm user coll-path :read))

(defn- collection-writeable?
  "Checks to see if the suer has write permissions on coll-path. Only
   works for collection paths."
  [cm user coll-path]
  (validate-path-lengths coll-path)
  (collection-perm? cm user coll-path :write))

(defn- owns-collection?
  "Checks to see if the user has ownership permissions on coll-path. Only
   works for collection paths."
  [cm user coll-path]
  (validate-path-lengths coll-path)
  (collection-perm? cm user coll-path :own))

(defn perm-map
  [[perm-id username]]
  (let [enum-val (FilePermissionEnum/valueOf (Integer/parseInt perm-id))]
    {:user        username
     :permissions {:read  (or (= enum-val read-perm) (= enum-val own-perm))
                   :write (or (= enum-val write-perm) (= enum-val own-perm))
                   :own   (= enum-val own-perm)}}))

(defn perm-user->map
  [[perm-id username]]
  {:user       username
   :permission (fmt-perm perm-id)})

(defn list-user-perms
  [cm abs-path & {:keys [known-type] :or {known-type nil}}]
  (let [path' (ft/rm-last-slash abs-path)]
    (validate-path-lengths path')
    (case (or known-type (item/object-type cm path'))
      :file (mapv perm-map (ll/user-dataobject-perms cm path'))
      :dir  (mapv perm-map (ll/user-collection-perms cm path')))))

(defn list-user-perm
  [cm abs-path & {:keys [known-type] :or {known-type nil}}]
  (let [path' (ft/rm-last-slash abs-path)]
    (validate-path-lengths path')
    (case (or known-type (item/object-type cm path'))
      :file (mapv perm-user->map (ll/user-dataobject-perms cm path'))
      :dir  (mapv perm-user->map (ll/user-collection-perms cm path')))))

(defn- set-dataobj-perms-admin
  [{^DataObjectAO dataobj :dataObjectAO zone :zone} user fpath read? write? own?]
  (validate-path-lengths fpath)

  (cond
    own?   (.setAccessPermissionOwnInAdminMode dataobj zone fpath user)
    write? (.setAccessPermissionWriteInAdminMode dataobj zone fpath user)
    read?  (.setAccessPermissionReadInAdminMode dataobj zone fpath user)
    :else  (.removeAccessPermissionsForUserInAdminMode dataobj zone fpath user)))

(defn- set-dataobj-perms-proxied
  [{^DataObjectAO dataobj :dataObjectAO zone :zone} user fpath read? write? own?]
  (validate-path-lengths fpath)

  (cond
    own?   (.setAccessPermissionOwn dataobj zone fpath user)
    write? (.setAccessPermissionWrite dataobj zone fpath user)
    read?  (.setAccessPermissionRead dataobj zone fpath user)
    :else  (.removeAccessPermissionsForUser dataobj zone fpath user)))

(defn set-dataobj-perms
  [{^DataObjectAO dataobj :dataObjectAO zone :zone :as cm} user fpath read? write? own?]
  (if (proxied? cm)
    (set-dataobj-perms-proxied cm user fpath read? write? own?)
    (set-dataobj-perms-admin cm user fpath read? write? own?)))

(defn- set-coll-perms-admin
  [{^CollectionAO coll :collectionAO zone :zone} user fpath read? write? own? recursive?]
  (validate-path-lengths fpath)

  (cond
    own?   (.setAccessPermissionOwnAsAdmin coll zone fpath user recursive?)
    write? (.setAccessPermissionWriteAsAdmin coll zone fpath user recursive?)
    read?  (.setAccessPermissionReadAsAdmin coll zone fpath user recursive?)
    :else  (.removeAccessPermissionForUserAsAdmin coll zone fpath user recursive?)))

(defn- set-coll-perms-proxied
  [{^CollectionAO coll :collectionAO zone :zone} user fpath read? write? own? recursive?]
  (validate-path-lengths fpath)

  (cond
    own?   (.setAccessPermissionOwn coll zone fpath user recursive?)
    write? (.setAccessPermissionWrite coll zone fpath user recursive?)
    read?  (.setAccessPermissionRead coll zone fpath user recursive?)
    :else  (.removeAccessPermissionForUser coll zone fpath user recursive?)))

(defn set-coll-perms
  [{^CollectionAO coll :collectionAO zone :zone :as cm} user fpath read? write? own? recursive?]
  (if (proxied? cm)
    (set-coll-perms-proxied cm user fpath read? write? own? recursive?)
    (set-coll-perms-admin cm user fpath read? write? own? recursive?)))

(defn set-permissions
  ([cm user fpath read? write? own?]
     (set-permissions cm user fpath read? write? own? false))
  ([cm user fpath read? write? own? recursive?]
     (validate-path-lengths fpath)
     (case (item/object-type cm fpath)
      :file (set-dataobj-perms cm user fpath read? write? own?)
      :dir  (set-coll-perms cm user fpath read? write? own? recursive?))))

(defn set-permission
  ([cm user fpath permission]
     (set-permission cm user fpath permission false))
  ([cm user fpath permission recursive?]
     (validate-path-lengths fpath)
     (let [own?    (= :own permission)
           write?  (or own? (= :write permission))
           read?   (or write? (= :read permission))]
      (set-permissions cm user fpath read? write? own? recursive?))))

(defn remove-permissions
  "Remove permissions, recursively where applicable"
  [cm user fpath]
  (set-permissions cm user fpath false false false true))

(defn remove-access-permissions
  "Remove permissions, non-recursively where applicable"
  [cm user abs-path]
  (set-permissions cm user abs-path false false false false))

(defn one-user-to-rule-them-all?
  [{^CollectionAndDataObjectListAndSearchAO lister :lister :as cm} user]
  (let [subdirs      (.listCollectionsUnderPathWithPermissions lister (ft/rm-last-slash (:home cm)) 0)
        get-perms    (fn [^CollectionAndDataObjectListingEntry d] (.getUserFilePermission d))
        get-username (fn [^UserFilePermission u] (.getUserName u))
        accessible?  (fn [u d] (some #(= (get-username %) u) (get-perms d)))]
    (every? (partial accessible? user) subdirs)))

(defn set-owner
  "Sets the owner of 'path' to the username 'owner'.

    Parameters:
      cm - The iRODS context map
      path - The path whose owner is being set.
      owner - The username of the user who will be the owner of 'path'."
  [{^DataObjectAO data-ao :dataObjectAO
    ^CollectionAO collection-ao :collectionAO
    zone :zone
    :as cm} path owner & {:keys [known-type] :or {known-type nil}}]
  (validate-path-lengths path)
  (case (or known-type (item/object-type cm path))
    :file (.setAccessPermissionOwn data-ao zone path owner)

    :dir  (.setAccessPermissionOwn collection-ao zone path owner true)))

(defn set-inherits
  "Sets the inheritance attribute of a collection to true (recursively).

    Parameters:
      cm - The iRODS context map
      path - The path being altered."
  [{^CollectionAO collection-ao :collectionAO zone :zone :as cm} path]
  (validate-path-lengths path)
  (when (item/is-dir? cm path)
    (.setAccessPermissionInherit collection-ao zone path true)))

(defn remove-inherits
  "Sets the inheritance attribute of a collection to false (recursively).

     Parameters:
       cm - The iRODS context map
       path - the path being altered."
  [{^CollectionAO collection-ao :collectionAO zone :zone :as cm} path]
  (validate-path-lengths path)
  (when (item/is-dir? cm path)
    (.setAccessPermissionToNotInherit collection-ao zone path true)))

(defn ^Boolean permissions-inherited?
  "Determines whether the inheritance attribute of a collection is true.

    Parameters:
      cm - The iRODS context map
      path - The path being checked."
  [{^CollectionAO collection-ao :collectionAO :as cm} path]
  (validate-path-lengths path)
  (boolean (when (item/is-dir? cm path)
    (.isCollectionSetForPermissionInheritance collection-ao path))))

(defn ^Boolean is-writeable?
  "Returns true if 'user' can write to 'path'.

    Parameters:
      cm - The iRODS context map
      user - String containign a username.
      path - String containing an absolute path for something in iRODS."
  [cm user path & {:keys [known-type] :or {known-type nil}}]
  (validate-path-lengths path)
  (if-not (user-exists? cm user)
    false
    (case (or known-type (item/object-type cm path))
      :dir  (boolean (collection-writeable? cm user (ft/rm-last-slash path)))
      :file (boolean (dataobject-writeable? cm user (ft/rm-last-slash path)))
      false)))

(defn ^Boolean is-readable?
  "Returns true if 'user' can read 'path'.

    Parameters:
      cm - The iRODS context map
      user - String containing a username.
      path - String containing an path for something in iRODS."
  [cm user path & {:keys [known-type] :or {known-type nil}}]
  (validate-path-lengths path)
  (if-not (user-exists? cm user)
    false
    (case (or known-type (item/object-type cm path))
      :dir  (boolean (collection-readable? cm user (ft/rm-last-slash path)))
      :file (boolean (dataobject-readable? cm user (ft/rm-last-slash path)))
      false)))

(defn ^Boolean paths-writeable?
  "Returns true if all of the paths in 'paths' are writeable by 'user'.

    Parameters:
      cm - The iRODS context map
      user - A string containing the username of the user requesting the check.
      paths - A sequence of strings containing the paths to be checked."
  [cm user paths]
  (doseq [p paths] (validate-path-lengths p))
  (reduce
    #(and %1 %2)
    (map
      #(is-writeable? cm user %)
      paths)))

(defn process-perms
  "Fetches the permissions on `path`, remove anything relating to admin users
  or `user` or the context-map user, then map `f` over the permissions"
  [f cm path user admin-users]
  (->> (list-user-perms cm path)
    (log-last)
    (remove #(contains? (set (conj admin-users user (:username cm))) (:user %)))
    (log-last)
    (map f)
    (log-last)
    (dorun)))

(defn process-parent-dirs
  [f process? path]
  (log/warn "in process-parent-dirs")
  (loop [dir-path (ft/dirname path)]
    (log/warn "processing path " dir-path)
    (when (process? dir-path)
      (log/warn "processing directory:" dir-path)
      (f dir-path)
      (recur (ft/dirname dir-path)))))

(defn set-readable
  [cm username readable? path]
  (let [{curr-write :write curr-own :own} (permissions cm username path)]
    (set-permissions cm username path readable? curr-write curr-own)))

(defn list-paths
  "Returns a list of paths for the entries under the parent path.  This is not
   recursive .

   Parameters:
     cm - The context map
     parent-path - The path of the parrent collection (directory).
     :ignore-child-exns - When this flag is provided, child names that are too
       long will not cause an exception to be thrown.  If they are not ignored,
       an exception will be thrown, causing no paths to be listed.  An ignored
       child will be replaced with a nil in the returned list.

   Returns:
     It returns a list path names for the entries under the parent.

   Throws:
     FileNotFoundException - This is thrown if parent-path is not in iRODS.
     See validate-path-lengths for path-related exceptions."
  [{^IRODSFileSystemAO cm-ao :fileSystemAO :as cm} parent-path & flags]
  (validate-path-lengths parent-path)
  (mapv
    #(try+
       (ft/path-join parent-path %1)
       (catch Object _
         (when-not (contains? (set flags) :ignore-child-exns) (throw+))))
    (.getListInDir cm-ao (item/file cm parent-path))))

(defn contains-accessible-obj?
  [cm user dpath]
  (log/warn "in contains-accessible-obj? - " user " " dpath)
  (let [retval (list-paths cm dpath)]
    (log/warn "results of list-paths " retval)
    (some #(is-readable? cm user %1) retval)))

(defn reset-perms
  [cm path user admin-users]
  (process-perms
   #(set-permissions cm (:user %) path false false false true)
   cm path user admin-users))

(defn inherit-perms
  [cm path user admin-users]
  (let [parent (ft/dirname path)]
    (process-perms
     (fn [{sharee :user {r :read w :write o :own} :permissions}]
       (set-permissions cm sharee path r w o true))
     cm parent user admin-users)))

(defn remove-obsolete-perms
  "Removes permissions that are no longer required for a directory that isn't shared.  Read
   permissions are no longer required for any user who no longer has access to any file or
   subdirectory."
  [cm path user admin-users]
  (let [user-hm   (ft/rm-last-slash (ft/path-join "/" (:zone cm) "home" user))
        parent    (ft/dirname path)
        base-dirs #{(ft/rm-last-slash (:home cm))
                    (item/trash-base-dir (:zone cm))
                    user-hm}]
    (process-perms
     (fn [{sharee :user}]
       (process-parent-dirs
        (partial set-readable cm sharee false)
        #(and (not (base-dirs %)) (not (contains-accessible-obj? cm sharee %)))
        path))
     cm parent user admin-users)))

(defn make-file-accessible
  "Ensures that a file is accessible to all users that have access to the file."
  [cm path user admin-users]
  (let [base-dirs #{(ft/rm-last-slash (:home cm)) (item/trash-base-dir (:zone cm))}]
    (process-perms
     (fn [{sharee :user}]
       (process-parent-dirs (partial set-readable cm sharee true) #(not (base-dirs %)) path))
     cm path user admin-users)))

(def ^:private perm-fix-fns
  "Functions used to update permissions after something is moved, indexed by the inherit flag
   of the source directory followed by the destination directory."
  {true  {true  (fn [cm src dst user admin-users skip-source-perms?]
                  (reset-perms cm dst user admin-users)
                  (inherit-perms cm dst user admin-users))

          false (fn [cm src dst user admin-users skip-source-perms?]
                  (reset-perms cm dst user admin-users))}

   false {true  (fn [cm src dst user admin-users skip-source-perms?]
                  (when-not skip-source-perms?
                    (remove-obsolete-perms cm src user admin-users))
                  (reset-perms cm dst user admin-users)
                  (inherit-perms cm dst user admin-users))

          false (fn [cm src dst user admin-users skip-source-perms?]
                  (when-not skip-source-perms?
                    (remove-obsolete-perms cm src user admin-users))
                  (make-file-accessible cm dst user admin-users))}})

(defn fix-perms
  [cm ^IRODSFile src ^IRODSFile dst user admin-users skip-source-perms?]
  (let [src-dir       (ft/dirname src)
        dst-dir       (ft/dirname dst)]
    (when-not (= src-dir dst-dir)
      ((get-in perm-fix-fns (mapv #(permissions-inherited? cm %) [src-dir dst-dir]))
       cm (.getPath src) (.getPath dst) user admin-users skip-source-perms?))))

(defn permissions
  [cm user fpath & {:keys [known-type] :or {known-type nil}}]
  (validate-path-lengths fpath)
  (case (or known-type (item/object-type cm fpath))
    :dir  (collection-perm-map cm user fpath)
    :file (dataobject-perm-map cm user fpath)
    {:read false
     :write false
     :own false}))

(defn permission-for
  "Determines a given user's permission for a given collection or data object.

   Parameters:
     cm - The context for an open connection to iRODS.
     user - the user name
     fpath - the logical path of the collection or data object.

   Returns:
     It returns the aggregated permission."
  [cm user fpath & {:keys [known-type] :or {known-type nil}}]
  (-> (case (or known-type (item/object-type cm fpath))
        :dir  (set (user-collection-perms* cm user fpath (partial = own-perm)))
        :file (set (user-dataobject-perms* cm user fpath (partial = own-perm))))
    max-perm
    fmt-perm))

(defn owns?
  [cm user fpath & {:keys [known-type] :or {known-type nil}}]
  (validate-path-lengths fpath)
  (case (or known-type (item/object-type cm fpath))
    :file (boolean (owns-dataobject? cm user fpath))
    :dir  (boolean (owns-collection? cm user fpath))
    false))

(defn removed-owners
  [curr-user-perms set-of-new-owners]
  (filterv
    #(not (contains? set-of-new-owners %1))
    (map :user curr-user-perms)))

(defn fix-owners
  [cm abs-path & owners]
  (validate-path-lengths abs-path)
  (let [curr-user-perms   (list-user-perms cm abs-path)
        set-of-new-owners (set owners)
        rm-zone           #(if (string/split %1 #"\#")
                             (first (string/split %1 #"\#"))
                             "")]
    (doseq [non-user (filterv
                      #(not (contains? set-of-new-owners %1))
                      (map :user curr-user-perms))]
      (remove-access-permissions cm non-user abs-path))

    (doseq [new-owner set-of-new-owners]
      (set-owner cm abs-path new-owner))))
