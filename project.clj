(defproject org.cyverse/clj-jargon "3.1.6-SNAPSHOT"
  :description "Clojure API on top of iRODS's jargon-core."
  :url "https://github.com/cyverse-de/clj-jargon"
  :license {:name "BSD"
            :url "https://cyverse.org/license"}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :plugins [[jonase/eastwood "1.4.3"]
            [lein-ancient "1.0.0"]
            [test2junit "1.4.4"]]
  ;; Records versions Leiningen already resolves, read off the resolved
  ;; classpath rather than copied from lein's "Consider using these
  ;; :managed-dependencies" hint -- that hint names the version that LOST the
  ;; conflict, so pasting it would be a silent upgrade. Most of these arbitrate
  ;; metosin/compojure-api 1.1.14, the final release of an archived project whose
  ;; transitives disagree with each other, and clj-http vs buddy-core.
  ;;
  ;; The jackson-* entries hold the family at the 2.14.1 that jargon-core
  ;; 4.3.7.0-RELEASE brings. jargon-core is pinned :upgrade false for iRODS
  ;; compatibility, so jackson cannot move ahead of it without moving jargon too.
  :managed-dependencies [[cheshire "5.13.0"]
                         [com.fasterxml.jackson.core/jackson-annotations "2.14.1"]
                         [com.fasterxml.jackson.core/jackson-core "2.14.1"]
                         [com.fasterxml.jackson.core/jackson-databind "2.14.1"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.14.1"]
                         [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.14.1"]
                         [commons-codec "1.15"]
                         [commons-io "2.11.0"]
                         [prismatic/schema "1.1.12"]
                         [ring/ring-codec "1.1.0"]
                         [ring/ring-core "1.6.3"]]
  ;; Fail the build on a new dependency conflict rather than printing a
  ;; warning nobody reads.
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [org.clojure/tools.logging "1.3.1"]
                 [org.irods.jargon/jargon-core "4.3.7.0-RELEASE"
                  :upgrade false
                  :exclusions [[org.jglobus/JGlobus-Core]
                               [org.slf4j/slf4j-api]
                               [org.slf4j/slf4j-log4j12]]]
                 [org.irods.jargon/jargon-data-utils "4.3.7.0-RELEASE"
                  :upgrade false
                  :exclusions [[org.slf4j/slf4j-api]
                               [org.slf4j/slf4j-log4j12]]]
                 [org.irods.jargon/jargon-ticket "4.3.7.0-RELEASE"
                  :upgrade false
                  :exclusions [[org.slf4j/slf4j-api]
                               [org.slf4j/slf4j-log4j12]]]
                 [cheshire "5.13.0"
                  :exclusions [[com.fasterxml.jackson.core/jackson-databind]]]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.14.1"
                  :exclusions [[com.fasterxml.jackson.core/jackson-databind]]]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.14.1"
                  :exclusions [[com.fasterxml.jackson.core/jackson-databind]]]
                 [dev.weavejester/medley "1.10.0"]
                 [slingshot "0.12.2"]
                 [org.cyverse/clojure-commons "3.0.13"]]
  :profiles {:repl {:source-paths ["repl"]}}
  :eastwood {:exclude-linters [:unlimited-use :non-dynamic-earmuffs]}
  :repositories [["cyverse-de"
                  {:url "https://raw.github.com/cyverse-de/mvn/master/releases"}]
                 ["dice.repository"
                  {:url "https://raw.github.com/DICE-UNC/DICE-Maven/master/releases"}]])
