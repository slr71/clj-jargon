(defproject org.cyverse/clj-jargon "3.1.5-SNAPSHOT"
  :description "Clojure API on top of iRODS's jargon-core."
  :url "https://github.com/cyverse-de/clj-jargon"
  :license {:name "BSD"
            :url "https://cyverse.org/license"}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :plugins [[jonase/eastwood "1.4.3"]
            [lein-ancient "0.7.0"]
            [test2junit "1.4.4"]]
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
                 [org.cyverse/clojure-commons "3.0.12"]]
  :profiles {:repl {:source-paths ["repl"]}}
  :eastwood {:exclude-linters [:unlimited-use :non-dynamic-earmuffs]}
  :repositories [["cyverse-de"
                  {:url "https://raw.github.com/cyverse-de/mvn/master/releases"}]
                 ["dice.repository"
                  {:url "https://raw.github.com/DICE-UNC/DICE-Maven/master/releases"}]])
