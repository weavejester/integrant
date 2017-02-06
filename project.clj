(defproject integrant "0.2.1"
  :description "Micro-framework for data-driven architecture"
  :url "https://github.com/weavejester/integrant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.stuartsierra/dependency "0.2.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojurescript "1.7.228"]]}}
  :plugins [[lein-doo "0.1.7"]]
  :cljsbuild
  {:builds [{:id "test-phantom"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/cljs/test-phantom/test-integrant.js"
                        :output-dir "target/cljs/test-phantom/out"
                        :main integrant.test-runner
                        :optimizations :none}}
            {:id "test-nashorn"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/cljs/test-nashorn/test-integrant.js"
                        :output-dir "target/cljs/test-nashorn/out"
                        :main integrant.test-runner
                        :optimizations :simple}}
            {:id "test-node"
             :source-paths ["src" "test"]
             :compiler {:target :nodejs
                        :output-to  "target/cljs/test-node/test-integrant.js"
                        :output-dir "target/cljs/test-node/out"
                        :main integrant.test-runner
                        :optimizations :none}}]}
  :aliases {"test-phantom" ["doo" "phantom" "test-phantom" "once"]
            "test-nashorn" ["doo" "nashorn" "test-nashorn" "once"]
            "test-node"    ["doo" "node" "test-node" "once"]
            "test-cljs"    ["do" ["test-phantom"] ["test-nashorn"] ["test-node"]]
            "test-all"     ["do" ["test"] ["test-cljs"]]
            "test"         ["test" ":only" "integrant.core-test"]})

