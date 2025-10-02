(ns integrant.core-test
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test :refer-macros [are deftest is testing]])
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [weavejester.dependency :as dep]))

(def log (atom []))

(defmethod ig/expand-key ::mod   [_ v] {::a v, ::b {:v v}})
(defmethod ig/expand-key ::mod-a [_ v] {::a v})
(defmethod ig/expand-key ::mod-b [_ v] {::b {:v v}})
(defmethod ig/expand-key ::mod-c [_ v] {::c {:x {:y {:z v}}}})
(defmethod ig/expand-key ::mod-z [_ v] {::z v})

(defmethod ig/expand-key ::mod-prof [_ v]
  {::a (ig/profile :dev {:dev v}, :test {:test v})})

(defmethod ig/init-key ::default [k v]
  (swap! log conj [:init k v])
  [v])

(doseq [k [::a ::b ::c ::pp :a/a1 :a/a2 :a/a3 :a/a4 :a/a5 :a/a6
           :a/a7 :a/a8 :a/a9 :a/a10 ::error-halt]]
  (derive k ::default))

(defmethod ig/init-key ::x [k v]
  (swap! log conj [:init k v])
  :x)

(prefer-method ig/init-key ::x ::default)

(defmethod ig/init-key ::error-init [_ _]
  (throw (ex-info "Testing" {:reason ::test})))

(defmethod ig/init-key ::k [_ v] v)

(defmethod ig/init-key ::n [_ v] (inc v))
(defmethod ig/assert-key ::n [_ v]
  (assert (nat-int? v) "should be a natural number"))

(defmethod ig/init-key ::r [_ v] {:v v})
(defmethod ig/resolve-key ::r [_ {:keys [v]}] v)
(defmethod ig/resume-key ::r [k v _ _] (ig/init-key k v))

(defmethod ig/halt-key! :default [k v]
  (swap! log conj [:halt k v]))

(defmethod ig/halt-key! ::error-halt [_ _]
  (throw (ex-info "Testing" {:reason ::test})))

(defmethod ig/resume-key :default [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  [cfg])

(defmethod ig/resume-key ::x [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  :rx)

(defmethod ig/suspend-key! :default [k v]
  (swap! log conj [:suspend k v]))

(derive ::p ::pp)
(derive ::pp ::ppp)

(derive ::ap ::a)
(derive ::ap ::p)

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- init-example [v]
  (str "init" v))

(deftest annotate-describe-test
  (ig/annotate ::foo {:doc "A test keyword"})
  (ig/annotate ::bar {:doc "Another test keyword"})
  (is (= {:doc "A test keyword"} (ig/describe ::foo)))
  (is (= {:doc "Another test keyword"} (ig/describe ::bar))))

(deftest ref-test
  (is (ig/ref? (ig/ref ::foo)))
  (is (ig/ref? (ig/ref [::foo ::bar])))
  (is (ig/reflike? (ig/ref ::foo)))
  (is (ig/reflike? (ig/ref [::foo ::bar]))))

(deftest refset-test
  (is (ig/refset? (ig/refset ::foo)))
  (is (ig/refset? (ig/refset [::foo ::bar])))
  (is (ig/reflike? (ig/refset ::foo)))
  (is (ig/reflike? (ig/refset [::foo ::bar]))))

(deftest composite-keyword-test
  (let [k (ig/composite-keyword [::a ::b])]
    (is (isa? k ::a))
    (is (isa? k ::b))
    (is (identical? k (ig/composite-keyword [::a ::b])))
    (is (not= k (ig/composite-keyword [::a ::c])))))

(deftest valid-config-key-test
  (is (ig/valid-config-key? ::a))
  (is (not (ig/valid-config-key? :a))))

#?(:clj
   (deftest read-string-test
     (is (= (ig/read-string "{:foo/a #ig/ref :foo/b, :foo/b 1}")
            {:foo/a (ig/ref :foo/b), :foo/b 1}))
     (is (= (ig/read-string "{:foo/a #ig/refset :foo/b, :foo/b 1}")
            {:foo/a (ig/refset :foo/b), :foo/b 1}))
     (is (= (ig/read-string "{:foo/a #ig/profile {:dev 1, :test 2}}")
            {:foo/a (ig/profile {:dev 1, :test 2})}))
     (is (= (ig/read-string "{:foo/a #ig/var port}")
            {:foo/a (ig/var 'port)}))
     (is (= (ig/read-string {:readers {'test/var find-var}}
                            "{:foo/a #test/var clojure.core/+}")
            {:foo/a #'+}))))

#?(:clj
   (defn- remove-lib [lib]
     (remove-ns lib)
     (dosync (alter @#'clojure.core/*loaded-libs* disj lib))))

(derive :integrant.test-child/foo :integrant.test/foo)

#?(:clj
   (deftest load-hierarchy-test
     (try
       (ig/load-hierarchy)
       (is (isa? :example/child :example/father))
       (is (isa? :example/child :example/mother))
       (finally
         (underive :example/child :example/father)
         (underive :example/child :example/mother)))))

#?(:clj
   (deftest load-annotations-test
     (try
       (ig/load-annotations)
       (is (= {:doc "A test keyword."} (ig/describe ::a)))
       (finally
         (ig/annotate ::a {})))))

#?(:clj
   (deftest load-namespaces-test
     (testing "all namespaces"
       (remove-lib 'integrant.test.foo)
       (remove-lib 'integrant.test.bar)
       (remove-lib 'integrant.test.baz)
       (remove-lib 'integrant.test.quz)
       (is (= (set (ig/load-namespaces
                    {:integrant.test/foo                     1
                     :integrant.test.bar/wuz                 2
                     [:integrant.test/baz :integrant.test/x] 3
                     [:integrant.test/y :integrant.test/quz] 4}))
              '#{integrant.test.foo
                 integrant.test.bar
                 integrant.test.baz
                 integrant.test.quz}))
       (is (some? (find-ns 'integrant.test.foo)))
       (is (some? (find-ns 'integrant.test.bar)))
       (is (some? (find-ns 'integrant.test.baz)))
       (is (some? (find-ns 'integrant.test.quz)))
       (is (= (some-> 'integrant.test.foo/message find-var var-get) "foo"))
       (is (= (some-> 'integrant.test.bar/message find-var var-get) "bar"))
       (is (= (some-> 'integrant.test.baz/message find-var var-get) "baz"))
       (is (= (some-> 'integrant.test.quz/message find-var var-get) "quz")))

     (testing "some namespaces"
       (remove-lib 'integrant.test.foo)
       (remove-lib 'integrant.test.bar)
       (remove-lib 'integrant.test.baz)
       (remove-lib 'integrant.test.quz)
       (is (= (set (ig/load-namespaces
                    {:integrant.test/foo 1
                     :integrant.test/bar (ig/ref :integrant.test/foo)
                     :integrant.test/baz 3}
                    [:integrant.test/bar]))
              '#{integrant.test.foo
                 integrant.test.bar}))
       (is (some? (find-ns 'integrant.test.foo)))
       (is (some? (find-ns 'integrant.test.bar)))
       (is (nil?  (find-ns 'integrant.test.baz))))

     (testing "load namespaces of ancestors"
       (remove-lib 'integrant.test.foo)
       (is (= (set (ig/load-namespaces
                    {:integrant.test-child/foo 1}))
              '#{integrant.test.foo}))
       (is (some? (find-ns 'integrant.test.foo))))))

(deftest dependency-graph-test
  (let [m {::a (ig/ref ::p), ::b (ig/refset ::ppp) ::p 1, ::pp 2}]
    (testing "graph with refsets"
      (let [g (ig/dependency-graph m)]
        (is (dep/depends? g ::a ::p))
        (is (dep/depends? g ::b ::p))
        (is (dep/depends? g ::b ::pp))))

    (testing "graph without refsets"
      (let [g (ig/dependency-graph m {:include-refsets? false})]
        (is (dep/depends? g ::a ::p))
        (is (not (dep/depends? g ::b ::p)))
        (is (not (dep/depends? g ::b ::pp)))))))

(deftest key-comparator-test
  (let [graph (ig/dependency-graph {::a (ig/ref ::ppp) ::p 1, ::b 2})]
    (is (= (sort (ig/key-comparator graph) [::b ::a ::p])
           [::p ::a ::b]))))

(deftest derived-from?-test
  (are [a b] (ig/derived-from? a b)
    ::p           ::p
    ::p           ::pp
    ::p           ::ppp
    ::ap          [::a ::p]
    ::ap          [::a ::pp]
    [::a ::p]     [::a ::pp]
    [::a ::b ::p] [::a ::ppp]))

(deftest find-derived-1-test
  (testing "missing key"
    (is (nil? (ig/find-derived-1 {} ::p))))

  (testing "derived key"
    (is (= (ig/find-derived-1 {::a "x" ::p "y"} ::pp)
           [::p "y"])))

  (testing "ambiguous key"
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

  (testing "ambiguous key"
    (is (= (ig/find-derived {::a "x" ::p "y" ::pp "z"} ::ppp)
           [[::p "y"] [::pp "z"]])))

  (testing "composite key"
    (is (= (ig/find-derived {::a "x" [::b ::x] "y", [::b ::y] "z"} ::b)
           [[[::b ::x] "y"] [[::b ::y] "z"]]))))

(deftest converge-test
  (testing "merge"
    (is (= (ig/converge {:a {:x 1}, :b {:y 2}})
           {:x 1, :y 2}))
    (is (= (ig/converge {:a {:x {:y 1}}, :b {:x {:z 2}}})
           {:x {:y 1, :z 2}}))
    (is (= (ig/converge {:a {}, :b {:y 2}})
           {:y 2}))
    (is (= (ig/converge {:a {:x 1}, :b {}})
           {:x 1})))

  (testing "overrides"
    (is (= (ig/converge {:a {:x 1}, :b {:x 2}} {:x 2})
           {:x 2}))
    (is (= (ig/converge {:a {:x {:y 1}}, :b {:x {:y 2}}} {:x {:y 2}})
           {:x {:y 2}}))
    (is (= (ig/converge {:a {:x {:y 1}}, :b {:x {:y 2}}} {:x {:y 3}})
           {:x {:y 3}})))

  (testing "conflicts"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Conflicting values at index "
                          "\\[:x\\] when converging: :a, :b\\."))
         (ig/converge {:a {:x 1}, :b {:x 2}})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Conflicting values at index "
                          "\\[:x\\] when converging: :a, :b\\."))
         (ig/converge {:a {:x 1}, :b {:x 2}})))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Conflicting values at index "
                          "\\[:x :y\\] when converging: :a, :b\\."))
         (ig/converge {:a {:x {:y 1}}, :b {:x {:y 2, :z 3}}})))))

(defrecord TestRecord [x])

(deftest expand-test
  (testing "default expand"
    (is (= (ig/expand {::unique 1})
           {::unique 1})))
  (testing "empty map values"
    (is (= (ig/expand {::unique {}})
           {::unique {}}))
    (is (= (ig/expand {::a {}, ::mod-a {:x 1}})
           {::a {:x 1}}))
    (is (= (ig/expand {::z {}, ::mod-z {:x 1}})
           {::z {:x 1}})))
  (testing "single expand"
    (is (= (ig/expand {::mod 1})
           {::a 1, ::b {:v 1}})))
  (testing "expand with unrelated keys"
    (is (= (ig/expand {::mod 1, ::b {:x 1}, ::c 2})
           {::a 1, ::b {:v 1, :x 1}, ::c 2})))
  (testing "expand with direct override"
    (is (= (ig/expand {::mod {:x 1}, ::a {:x 2}})
           {::a {:x 2}, ::b {:v {:x 1}}})))
  (testing "expand with nested override"
    (is (= (ig/expand {::mod-c 1, ::c {:x {:y {:z 2}}}})
           {::c {:x {:y {:z 2}}}})))
  (testing "expand with default override"
    (is (= (ig/expand {::mod 1, ::a 2}) {::a 2, ::b {:v 1}}))
    (is (= (ig/expand {::mod 1, ::b {:v 2}}) {::a 1, ::b {:v 2}})))
  (testing "unresolved conflicting index"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Conflicting values at index "
                          "\\[:integrant\\.core-test/a\\] "
                          "when converging: :integrant\\.core-test/mod, "
                          ":integrant\\.core-test/mod-a\\."))
         (ig/expand {::mod 1, ::mod-a 2}))))
  (testing "unresolved conflicting nested index"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Conflicting values at index "
                          "\\[:integrant\\.core-test/b :v\\] "
                          "when converging: :integrant\\.core-test/mod, "
                          ":integrant\\.core-test/mod-b\\."))
         (ig/expand {::mod 1, ::mod-b 2}))))
  (testing "conflicting keys with same value"
    (is (= (ig/expand {::mod {:x 1} ::mod-a {:x 1}})
           {::a {:x 1}, ::b {:v {:x 1}}}))
    (is (= (ig/expand {::mod {:x 1} ::mod-a {:x 1} ::mod-b {:x 1}})
           {::a {:x 1}, ::b {:v {:x 1}}})))
  (testing "resolved conflict"
    (is (= (ig/expand {::mod {:x 1}, ::mod-a {:x 2}, ::a {:x 3}})
           {::a {:x 3}, ::b {:v {:x 1}}})))
  (testing "resolved nested conflict"
    (is (= (ig/expand {::mod 1, ::mod-b 2, ::b {:v 3}})
           {::a 1, ::b {:v 3}}))
    (is (= (ig/expand {[::one ::mod-c] 1
                       [::two ::mod-c] 2
                       ::c {:x {:y {:z 3}}}})
           {::c {:x {:y {:z 3}}}})))
  (testing "expand with refs"
    (let [m {::a (ig/ref ::b) ::b 1}]
      (is (= m (ig/expand m))))
    (let [m {::a (ig/refset ::b) ::b 1}]
      (is (= m (ig/expand m)))))
  (testing "expand with records"
    (let [m {::a (->TestRecord 1), ::b 1}]
      (is (= m (ig/expand m)))))
  (testing "expand with inner function"
    (letfn [(walk-inc [x]
              (walk/postwalk #(if (int? %) (inc %) %) x))]
      (is (= {::a {:x 2}, ::b {:v {:x 2}}}
             (ig/expand {::mod {:x 1}} walk-inc))))))

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
                   [:init ::a :x]]))))

  (testing "with composite refs"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref [::b ::c]), [::b ::c ::e] 1, [::b ::d] 2})]
      (is (= m {::a [[1]], [::b ::c ::e] [1], [::b ::d] [2]}))
      (is (or (= @log [[:init [::b ::c ::e] 1]
                       [:init ::a [1]]
                       [:init [::b ::d] 2]])
              (= @log [[:init [::b ::d] 2]
                       [:init [::b ::c ::e] 1]
                       [:init ::a [1]]])))))

  (testing "with failing composite refs"
    (reset! log [])
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "^Invalid composite key: "
                          "\\[:integrant.core-test/a :b\\]. "
                          "Every keyword must be namespaced.$"))
         (ig/init {[::a :b] :anything}))))

  (testing "with custom resolve-key"
    (let [m (ig/init {::a (ig/ref ::r), ::r 1})]
      (is (= m {::a [1], ::r {:v 1}}))))

  (testing "with refsets"
    (reset! log [])
    (let [m (ig/init {::a (ig/refset ::ppp), ::p 1, ::pp 2})]
      (is (= m {::a [#{[1] [2]}], ::p [1], ::pp [2]}))
      (is (= @log [[:init ::p 1]
                   [:init ::pp 2]
                   [:init ::a #{[1] [2]}]]))))

  (testing "with refsets and keys"
    (reset! log [])
    (let [m {::a (ig/refset ::ppp), ::p 1, ::pp 2}]
      (is (= (ig/init m [::a])      {::a [#{}]}))
      (is (= (ig/init m [::a ::p])  {::a [#{[1]}] ::p [1]}))
      (is (= (ig/init m [::a ::pp]) {::a [#{[1] [2]}] ::p [1] ::pp [2]}))))

  (testing "large config"
    (is (= (ig/init {:a/a1 {} :a/a2 {:_ (ig/ref :a/a1)}
                     :a/a3 {} :a/a4 {} :a/a5 {}
                     :a/a6 {} :a/a7 {} :a/a8 {}
                     :a/a9 {} :a/a10 {}})
           {:a/a1 [{}] :a/a2 [{:_ [{}]}]
            :a/a3 [{}] :a/a4 [{}] :a/a5 [{}]
            :a/a6 [{}] :a/a7 [{}] :a/a8 [{}]
            :a/a9 [{}] :a/a10 [{}]})))

  (testing "with passing specs"
    (let [m (ig/init {::n (ig/ref ::k), ::k 1})]
      (is (= m {::n 2, ::k 1}))))

  (testing "with failing asserts"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Assertion failed on key " ::n
                          " when building system"))
         (ig/init {::n (ig/ref ::k), ::k 1.1}))))

  (testing "with failing composite specs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Assertion failed on key \\[" ::n " " ::nnn "\\] "
                          "when building system"))
         (ig/init {[::n ::nnn] 1.1}))))

  #?(:clj
     (testing "default functions"
       (is (= (ig/init {::init-example "foo"})
              {::init-example "initfoo"}))
       (is (= (ig/init {[::init-example ::undefined-composite] "foo"})
              {[::init-example ::undefined-composite] "initfoo"}))
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            (re-pattern (str "Error on key " ::init-examplo
                             " when building system"))
            (ig/init {::init-examplo "foo"}))))))

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

  (testing "with partial system"
    (reset! log [])
    (let [m (ig/init {::a 1, ::b (ig/ref ::a)} [::a])]
      (ig/halt! m)
      (is (= @log [[:init ::a 1]
                   [:halt ::a [1]]]))))

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
    (let [c {::a (ig/ref ::b), ::b 1}
          m (ig/init c)]
      (ig/suspend! m)
      (ig/resume c m)
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:resume ::b 1 1 [1]]
                   [:resume ::a [1] [1] [[1]]]]))))

  (testing "missing keys"
    (reset! log [])
    (let [c {::a (ig/ref ::b), ::b 1}
          m (ig/init c)]
      (ig/suspend! m)
      (ig/resume (dissoc c ::a) m)
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:halt ::a [[1]]]
                   [:resume ::b 1 1 [1]]]))))

  (testing "missing refs"
    (reset! log [])
    (let [c {::a {:b (ig/ref ::b)}, ::b 1}
          m (ig/init c)]
      (ig/suspend! m)
      (ig/resume {::a []} m)
      (is (= @log [[:init ::b 1]
                   [:init ::a {:b [1]}]
                   [:suspend ::a [{:b [1]}]]
                   [:suspend ::b [1]]
                   [:halt ::b [1]]
                   [:resume ::a [] {:b [1]} [{:b [1]}]]]))))

  (testing "with custom resolve-key"
    (let [c  {::a (ig/ref ::r), ::r 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= m m'))))

  (testing "composite keys"
    (reset! log [])
    (let [c {::a (ig/ref ::x), [::b ::x] 1}
          m (ig/init c)]
      (ig/suspend! m)
      (ig/resume c m)
      (is (= @log [[:init [::b ::x] 1]
                   [:init ::a :x]
                   [:suspend ::a [:x]]
                   [:suspend [::b ::x] :x]
                   [:resume [::b ::x] 1 1 :x]
                   [:resume ::a :rx :x [:x]]]))))

  (testing "resume key with dependencies"
    (reset! log [])
    (let [c {::a {:b (ig/ref ::b)}, ::b 1}
          m (ig/init c [::a])]
      (ig/suspend! m)
      (ig/resume c m [::a])
      (is (= @log
             [[:init ::b 1]
              [:init ::a {:b [1]}]
              [:suspend ::a [{:b [1]}]]
              [:suspend ::b [1]]
              [:resume ::b 1 1 [1]]
              [:resume ::a {:b [1]} {:b [1]} [{:b [1]}]]])))))

(deftest invalid-configs-test
  (testing "ambiguous refs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Ambiguous key: " ::ppp "\\. "
                          "Found multiple candidates: "
                          "(" ::p ", " ::pp "|" ::pp ", " ::p ")"))
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
    [(ig/build config (keys config)
               (fn [k v] (last (swap! log conj [:build k v]))))
     @log]))

(deftest build-test
  (is (= [{::a [:build ::a [:build ::b 1]]
           ::b [:build ::b 1]}
          [[:build ::b 1]
           [:build ::a [:build ::b 1]]]]
         (build-log {::a (ig/ref ::b)
                     ::b 1}))))

(defn test-log [f m]
  (let [log (atom [])]
    [(f m (keys m) (fn [k v] (last (swap! log conj [:test k v]))))
     @log]))

(deftest run-test
  (let [config {::a (ig/ref ::b), ::b 1}
        [system _] (build-log config)]
    (is (= [nil
            [[:test ::b [:build ::b 1]]
             [:test ::a [:build ::a [:build ::b 1]]]]]
           (test-log ig/run! system)))
    (is (= [nil
            [[:test ::a [:build ::a [:build ::b 1]]]
             [:test ::b [:build ::b 1]]]]
           (test-log ig/reverse-run! system)))))

(deftest fold-test
  (let [config {::a (ig/ref ::ppp), ::b (ig/ref ::pp), ::p 1, ::c 2}
        system (ig/init config)]
    (is (= (ig/fold system #(conj %1 [%2 %3]) [])
           [[::p [1]] [::a [[1]]] [::b [[1]]] [::c [2]]]))))

(deftest wrapped-exception-test
  (testing "exception when building"
    (let [ex (try (ig/init {::a 1, ::error-init (ig/ref ::a)}) nil
                  (catch #?(:clj Throwable :cljs :default) t t))]
      (is (some? ex))
      (is (= (#?(:clj .getMessage :cljs ex-message) ex)
             (str "Error on key " ::error-init " when building system")))
      (is (= (ex-data ex)
             {:reason   ::ig/build-threw-exception
              :system   {::a [1]}
              :function ig/init-key
              :key      ::error-init
              :value    [1]}))
      (let [cause (#?(:clj .getCause :cljs ex-cause) ex)]
        (is (some? cause))
        (is (= (#?(:clj .getMessage :cljs ex-message) cause) "Testing"))
        (is (= (ex-data cause) {:reason ::test})))))

  (testing "exception when running"
    (let [system (ig/init {::a 1
                           ::error-halt (ig/ref ::a)
                           ::b (ig/ref ::error-halt)
                           ::c (ig/ref ::b)})
          ex     (try (ig/halt! system)
                      (catch #?(:clj Throwable :cljs :default) t t))]
      (is (some? ex))
      (is (= (#?(:clj .getMessage :cljs ex-message) ex)
             (str "Error on key " ::error-halt " when running system")))
      (is (= (ex-data ex)
             {:reason         ::ig/run-threw-exception
              :system         {::a [1], ::error-halt [[1]]
                               ::b [[[1]]], ::c [[[[1]]]]}
              :completed-keys '(::c ::b)
              :remaining-keys '(::a)
              :function       ig/halt-key!
              :key            ::error-halt
              :value          [[1]]}))
      (let [cause (#?(:clj .getCause :cljs ex-cause) ex)]
        (is (some? cause))
        (is (= (#?(:clj .getMessage :cljs ex-message) cause) "Testing"))
        (is (= (ex-data cause) {:reason ::test}))))))

(deftest profile-test
  (testing "deprofiling"
    (is (= (ig/deprofile (ig/profile {:a 1}) [:a])
           1))
    (is (= (ig/deprofile (ig/profile {:a 1 :b 2}) [:b :a])
           2))
    (is (= (ig/deprofile {::x (ig/profile {:a 1 :b 2})
                          ::y (ig/profile {:b 1 :a 2})}
                         [:a])
           {::x 1, ::y 2}))
    (is (= (ig/deprofile {::x [1 2 (ig/profile {:a 1, :b 2, :c 3})]}
                         [:c :a])
           {::x [1 2 3]})))

  (testing "missing keys"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Missing a valid key for profile: #ig/profile "
                          "\\{:a 1, :b 2\\}"))
         (ig/deprofile (ig/profile {:a 1, :b 2}) [:c]))))

  (testing "syntax sugar"
    (is (= (ig/profile {:a 1})
           (ig/profile :a 1)))
    (is (= (ig/profile {:a 1, :b 2})
           (ig/profile :a 1 :b 2)))
    (let [p (ig/profile :a 1)]
      (is (= (ig/deprofile p [:a])
             ((ig/deprofile [:a]) p)))))

  (testing "expand and deprofile"
    (is (= (ig/expand {::mod-prof 1} (ig/deprofile [:dev]))
           {::a {:dev 1}}))
    (is (= (ig/expand {::mod-prof 2} (ig/deprofile [:test]))
           {::a {:test 2}}))
    (is (= (-> {::x (ig/profile {:a 1, :b 2})}
               (ig/expand (ig/deprofile [:a]))
               (ig/deprofile [:a]))
           {::x 1}))))

(deftest var-test
  (testing "var binding"
    (is (= {::x 1}
           (ig/bind {::x (ig/var 'v)} {'v 1})))
    (is (= {::x {:y 1}}
           (ig/bind {::x {:y (ig/var 'v)}} {'v 1})))
    (is (= {::x {:a 1, :b 2}}
           (ig/bind {::x {:a (ig/var 'a) :b (ig/var 'b)}}
                    {'a 1, 'b 2})))
    (is (= {::x {:y [1 2 3]}}
           (ig/bind {::x {:y [1 2 (ig/var 'z)]}} {'z 3}))))

  (testing "init with unbound vars"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern "Unbound vars: foo, bar")
         (ig/init {::x {:foo (ig/var 'foo), :bar (ig/var 'bar)}}))))

  (testing "expand with vars"
    (is (= (-> {::x (ig/var 'x)} (ig/expand) (ig/bind {'x 1}))
           {::x 1}))))
