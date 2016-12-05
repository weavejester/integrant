(ns integrant.core-test
  (:require [clojure.test :refer :all]
            [integrant.core :refer :all]))

(def log (atom []))

(defmethod init-key :default [k v]
  (swap! log conj [:init k v])
  [v])

(defmethod halt-key! :default [k v]
  (swap! log conj [:halt k v]))

(deftest ref-test
  (is (ref? (ref :foo))))

(deftest expand-test
  (is (= (expand {::a (ref ::b), ::b 1})
         {::a 1, ::b 1}))
  (is (= (expand {::a (ref ::b), ::b (ref ::c), ::c 2})
         {::a 2, ::b 2, ::c 2})))

(deftest init-test
  (reset! log [])
  (let [m (init {::a (ref ::b), ::b 1})]
    (is (= m {::a [[1]], ::b [1]}))
    (is (= @log [[:init ::b 1]
                 [:init ::a [1]]]))))

(deftest halt-test
  (reset! log [])
  (let [m (init {::a (ref ::b), ::b 1})]
    (halt! m)
    (is (= @log [[:init ::b 1]
                 [:init ::a [1]]
                 [:halt ::a [[1]]]
                 [:halt ::b [1]]]))))

(deftest missing-ref-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing definitions for refs: :integrant.core-test/b"
                        (init {::a (ref ::b)}))))
