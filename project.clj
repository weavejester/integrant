(defproject integrant "0.1.1"
  :description "Micro-framework for data-driven architecture"
  :url "https://github.com/weavejester/integrant"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.stuartsierra/dependency "0.2.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojurescript "1.7.228"]]}}
  :plugins [[lein-doo "0.1.7"]]
  :cljsbuild
  {:builds [{:id "phantom-test"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/cljs/phantom-test/integrant-test.js"
                        :output-dir "target/cljs/phantom-test/out"
                        :main integrant.test-runner
                        :optimizations :none}}
            {:id "nashorn-test"
             :source-paths ["src" "test"]
             :compiler {:output-to  "target/cljs/nashorn-test/integrant-test.js"
                        :output-dir "target/cljs/nashorn-test/out"
                        :main integrant.test-runner
                        ;; nashorn apparently doesn't support :none or :whitespace (??!)
                        :optimizations :simple}}
            {:id "node-test"
             :source-paths ["src" "test"]
             :compiler {:target :nodejs
                        :output-to  "target/cljs/node-test/integrant-test.js"
                        :output-dir "target/cljs/node-test/out"
                        :main integrant.test-runner
                        :optimizations :none}}]}
  :aliases {"phantom-test" ["doo" "phantom" "phantom-test" "once"]
            "nashorn-test" ["doo" "nashorn" "nashorn-test" "once"]
            "node-test"    ["doo" "node" "node-test" "once"]
            "cljs-test"    ["do" ["phantom-test"] ["nashorn-test"] ["node-test"]]
            "all-tests"    ["do" ["test"] ["cljs-test"]]})

