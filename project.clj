(defproject integrant "0.9.0-alpha2"
  :description "Micro-framework for data-driven architecture"
  :url "https://github.com/weavejester/integrant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [weavejester/dependency "0.2.1"]]
  :profiles {:provided {:dependencies [[org.clojure/clojurescript "1.10.597"]
                                       [org.clojure/tools.reader "1.3.6"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}}
  :plugins [[lein-codox "0.10.4"]
            [lein-doo "0.1.7"]]
  :codox
  {:output-path "codox"
   :source-uri "http://github.com/weavejester/integrant/blob/{version}/{filepath}#L{line}"}
  :cljsbuild
  {:builds [{:id "test-nashorn"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/cljs/test-nashorn/test-integrant.js"
                        :output-dir "target/cljs/test-nashorn/out"
                        :main integrant.test-runner
                        :optimizations :simple
                        :process-shim false}}
            {:id "test-node"
             :source-paths ["src" "test"]
             :compiler {:target :nodejs
                        :output-to  "target/cljs/test-node/test-integrant.js"
                        :output-dir "target/cljs/test-node/out"
                        :main integrant.test-runner
                        :optimizations :none
                        :process-shim false}}]}
  :aliases {"test-nashorn" ["doo" "nashorn" "test-nashorn" "once"]
            "test-node"    ["doo" "node" "test-node" "once"]
            "test-cljs"    ["do" ["test-nashorn"] ["test-node"]]
            "test-clj"     ["with-profile" "default:+1.10:+1.11"
                            "test" ":only" "integrant.core-test"]
            "test-all"     ["do" ["test-clj"] ["test-cljs"]]
            "test"         ["test" ":only" "integrant.core-test"]})

