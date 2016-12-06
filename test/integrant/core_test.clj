(ns integrant.core-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]))

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
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing definitions for refs: :integrant.core-test/b"
                        (ig/init {::a (ig/ref ::b)}))))
