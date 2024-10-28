(defproject integrant "0.13.1"
  :description "Micro-framework for data-driven architecture"
  :url "https://github.com/weavejester/integrant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [weavejester/dependency "0.2.1"]]
  :profiles {:provided {:dependencies [[org.clojure/clojurescript "1.11.132"]
                                       [org.clojure/tools.reader "1.5.0"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.0"]]}}
  :plugins [[lein-codox "0.10.8"]
            [lein-doo "0.1.11"]]
  :codox
  {:output-path "codox"
   :metadata {:doc/format :markdown}
   :source-uri "http://github.com/weavejester/integrant/blob/{version}/{filepath}#L{line}"}
  :cljsbuild
  {:builds [{:id "test-node"
             :source-paths ["src" "test"]
             :compiler {:target :nodejs
                        :output-to  "target/cljs/test-node/test-integrant.js"
                        :output-dir "target/cljs/test-node/out"
                        :main integrant.test-runner
                        :optimizations :none
                        :process-shim false}}]}
  :aliases {"test-node"    ["doo" "node" "test-node" "once"]
            "test-cljs"    ["do" ["test-node"]]
            "test-clj"     ["with-profile" "default:+1.12"
                            "test" ":only" "integrant.core-test"]
            "test-all"     ["do" ["test-clj"] ["test-cljs"]]
            "test"         ["test" ":only" "integrant.core-test"]})

