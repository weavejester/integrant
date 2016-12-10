(ns integrant.core-test
  (:require [integrant.core :as ig]
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is]])))
(def log (atom []))

(defmethod ig/init-key :default [k v]
  (swap! log conj [:init k v])
  [v])

(defmethod ig/halt-key! :default [k v]
  (swap! log conj [:halt k v]))

(deftest ref-test
  (is (ig/ref? (ig/ref :foo))))

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

(deftest init-test
  (reset! log [])
  (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
    (is (= m {::a [[1]], ::b [1]}))
    (is (= @log [[:init ::b 1]
                 [:init ::a [1]]]))))

(deftest halt-test
  (reset! log [])
  (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
    (ig/halt! m)
    (is (= @log [[:init ::b 1]
                 [:init ::a [1]]
                 [:halt ::a [[1]]]
                 [:halt ::b [1]]]))))

(deftest missing-ref-test
  (is (thrown-with-msg? #?(:clj  clojure.lang.ExceptionInfo
                           :cljs cljs.core.ExceptionInfo)
                        #"Missing definitions for refs: :integrant.core-test/b"
                        (ig/init {::a (ig/ref ::b)}))))
