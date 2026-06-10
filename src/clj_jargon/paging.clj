(ns clj-jargon.paging
  (:import
   [org.irods.jargon.core.pub.io
    FileIOOperations$SeekWhenceType
    IRODSRandomAccessFile
    IRODSFileFactory]))

(def SEEK-CURRENT (FileIOOperations$SeekWhenceType/SEEK_CURRENT))
(def SEEK-START (FileIOOperations$SeekWhenceType/SEEK_START))
(def SEEK-END (FileIOOperations$SeekWhenceType/SEEK_END))

(defn ^IRODSRandomAccessFile random-access-file
  [{^IRODSFileFactory file-factory :fileFactory} ^String filepath]
  (.instanceIRODSRandomAccessFile file-factory filepath))

(defn read-at-position
  ([cm filepath position num-bytes]
   (read-at-position cm filepath position num-bytes true))
  ([cm filepath position num-bytes stringify?]
   (let [access-file (random-access-file cm filepath)
         buffer      (byte-array num-bytes)]
     (let [_   (.seek access-file position SEEK-CURRENT)
           len (.read access-file buffer)
           _   (.close access-file)]
       (if stringify?
         (String. buffer 0 (max 0 len))
         (byte-array (take (max 0 len) buffer )))))))

(defn overwrite-at-position
  [cm filepath position update]
  (let [access-file  (random-access-file cm filepath)
        ^bytes update-bytes (.getBytes ^String update)
        read-buffer  (byte-array (count update-bytes))]
    (doto access-file
      (.seek position SEEK-CURRENT)
      (.write update-bytes)
      (.close))
    nil))
