(ns integrant.core-test
  (:require [integrant.core :as ig]
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing]])
            [com.stuartsierra.dependency :as dep]))

(def log (atom []))

(defmethod ig/init-key :default [k v]
  (swap! log conj [:init k v])
  [v])

(defmethod ig/init-key ::x [k v]
  (swap! log conj [:init k v])
  :x)

(defmethod ig/halt-key! :default [k v]
  (swap! log conj [:halt k v]))

(defmethod ig/resume-key :default [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  [cfg])

(defmethod ig/resume-key ::x [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  :rx)

(defmethod ig/suspend-key! :default [k v]
  (swap! log conj [:suspend k v]))

(deftest ref-test
  (is (ig/ref? (ig/ref :foo))))

(deftest composite-keyword-test
  (let [k (ig/composite-keyword [::a ::b])]
    (is (isa? k ::a))
    (is (isa? k ::b))
    (is (identical? k (ig/composite-keyword [::a ::b])))
    (is (not= k (ig/composite-keyword [::a ::c])))))

(deftest expand-test
  (is (= (ig/expand {::a (ig/ref ::b), ::b 1})
         {::a 1, ::b 1}))
  (is (= (ig/expand {::a (ig/ref ::b), ::b (ig/ref ::c), ::c 2})
         {::a 2, ::b 2, ::c 2})))

#?(:clj
   (deftest read-string-test
     (is (= (ig/read-string "{:foo/a #ref :foo/b, :foo/b 1}")
            {:foo/a (ig/ref :foo/b), :foo/b 1}))
     (is (= (ig/read-string {:readers {'var find-var}} "{:foo/a #var clojure.core/+}")
            {:foo/a #'+}))))

#?(:clj
   (defn- remove-lib [lib]
     (remove-ns lib)
     (dosync (alter @#'clojure.core/*loaded-libs* disj lib))))

#?(:clj
   (deftest load-namespaces-test
     (remove-lib 'integrant.test.foo)
     (remove-lib 'integrant.test.bar)
     (is (= (ig/load-namespaces {:integrant.test/foo 1, :integrant.test.bar/baz 2})
            '(integrant.test.foo integrant.test.bar)))
     (is (some? (find-ns 'integrant.test.foo)))
     (is (some? (find-ns 'integrant.test.bar)))
     (is (= (some-> 'integrant.test.foo/message find-var var-get) "foo"))
     (is (= (some-> 'integrant.test.bar/message find-var var-get) "bar"))))

(derive ::p ::pp)
(derive ::pp ::ppp)

(deftest dependency-graph-test
  (is (dep/depends? (ig/dependency-graph {::a (ig/ref ::ppp) ::p "b"}) ::a ::p)))

(deftest find-derived-1-test
  (testing "missing key"
    (is (nil? (ig/find-derived-1 {} ::p))))

  (testing "derived key"
    (is (= (ig/find-derived-1 {::a "x" ::p "y"} ::pp)
           [::p "y"])))

  (testing "ambigous key"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Ambiguous key: " ::pp "\\. "
                          "Found multiple candidates: " ::p ", " ::pp))
         (ig/find-derived-1 {::a "x" ::p "y", ::pp "z"} ::pp))))

  (testing "composite key"
    (is (= (ig/find-derived-1 {::a "x" [::b ::x] "y"} ::x)
           [[::b ::x] "y"]))))

(deftest find-derived-test
  (testing "missing key"
    (is (nil? (ig/find-derived {} ::p))))

  (testing "derived key"
    (is (= (ig/find-derived {::a "x" ::p "y" ::pp "z"} ::pp)
           [[::p "y"] [::pp "z"]])))

  (testing "ambigous key"
    (is (= (ig/find-derived {::a "x" ::p "y" ::pp "z"} ::ppp)
           [[::p "y"] [::pp "z"]])))

  (testing "composite key"
    (is (= (ig/find-derived {::a "x" [::b ::x] "y", [::b ::y] "z"} ::b)
           [[[::b ::x] "y"] [[::b ::y] "z"]]))))

(deftest init-test
  (testing "without keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
      (is (= m {::a [[1]], ::b [1]}))
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]]))))

  (testing "with keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1, ::c 2} [::a])]
      (is (= m {::a [[1]], ::b [1]}))
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]]))))

  (testing "with inherited keys"
    (reset! log [])
    (let [m (ig/init {::p (ig/ref ::a), ::a 1} [::pp])]
      (is (= m {::p [[1]], ::a [1]}))
      (is (= @log [[:init ::a 1]
                   [:init ::p [1]]]))))

  (testing "with composite keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), [::x ::b] 1})]
      (is (= m {::a [:x], [::x ::b] :x}))
      (is (= @log [[:init [::x ::b] 1]
                   [:init ::a :x]])))))

(deftest halt-test
  (testing "without keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
      (ig/halt! m)
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:halt ::a [[1]]]
                   [:halt ::b [1]]]))))

  (testing "with keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b (ig/ref ::c), ::c 1})]
      (ig/halt! m [::a])
      (is (= @log [[:init ::c 1]
                   [:init ::b [1]]
                   [:init ::a [[1]]]
                   [:halt ::a [[[1]]]]]))
      (reset! log [])
      (ig/halt! m [::c])
      (is (= @log [[:halt ::a [[[1]]]]
                   [:halt ::b [[1]]]
                   [:halt ::c [1]]]))))

  (testing "with inherited keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::p), ::p 1} [::a])]
      (ig/halt! m [::pp])
      (is (= @log [[:init ::p 1]
                   [:init ::a [1]]
                   [:halt ::a [[1]]]
                   [:halt ::p [1]]]))))

  (testing "with composite keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), [::x ::b] 1})]
      (ig/halt! m)
      (is (= @log [[:init [::x ::b] 1]
                   [:init ::a :x]
                   [:halt ::a [:x]]
                   [:halt [::x ::b] :x]])))))

(deftest suspend-resume-test
  (testing "same configuration"
    (reset! log [])
    (let [c  {::a (ig/ref ::b), ::b 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:resume ::b 1 1 [1]]
                   [:resume ::a [1] [1] [[1]]]]))))

  (testing "missing keys"
    (reset! log [])
    (let [c  {::a (ig/ref ::b), ::b 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume (dissoc c ::a) m)]
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:halt ::a [[1]]]
                   [:resume ::b 1 1 [1]]]))))

  (testing "composite keys"
    (reset! log [])
    (let [c  {::a (ig/ref ::x), [::b ::x] 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= @log [[:init [::b ::x] 1]
                   [:init ::a :x]
                   [:suspend ::a [:x]]
                   [:suspend [::b ::x] :x]
                   [:resume [::b ::x] 1 1 :x]
                   [:resume ::a :rx :x [:x]]])))))

(deftest invalid-configs-test
  (testing "ambiguous refs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Ambiguous key: " ::ppp "\\. "
                          "Found multiple candidates: " ::p ", " ::pp))
         (ig/init {::a (ig/ref ::ppp), ::p 1, ::pp 2}))))

  (testing "missing refs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Missing definitions for refs: " ::b))
         (ig/init {::a (ig/ref ::b)}))))

  (testing "missing refs with explicit keys"
    (is (= (ig/init {::a (ig/ref ::ppp), ::p 1, ::pp 2} [::p ::pp])
           {::p [1], ::pp [2]})))

  (testing "missing refs with explicit keys"
    (is (= (ig/init {::a 1, ::b (ig/ref ::c)} [::a])
           {::a [1]}))))

(defn build-log [config]
  (let [log (atom [])]
    [(ig/build config (keys config) (fn [k v] (last (swap! log conj [:build k v]))))
     @log]))

(deftest build-test
  (is (= [{:a [:build :a [:build :b 1]]
           :b [:build :b 1]}
          [[:build :b 1]
           [:build :a [:build :b 1]]]]
         (build-log {:a (ig/ref :b)
                     :b 1}))))

(defn test-log [f m]
  (let [log (atom [])]
    [(f m (keys m) (fn [k v] (last (swap! log conj [:test k v]))))
     @log]))

(deftest run-test
  (let [config {:a (ig/ref :b), :b 1}
        [system _] (build-log config)]
    (is (= [nil
            [[:test :b [:build :b 1]]
             [:test :a [:build :a [:build :b 1]]]]]
           (test-log ig/run! system)))
    (is (= [nil
            [[:test :a [:build :a [:build :b 1]]]
             [:test :b [:build :b 1]]]]
           (test-log ig/reverse-run! system)))))
