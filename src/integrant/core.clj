(ns integrant.core
  (:refer-clojure :exclude [ref read-string])
  (:require [com.stuartsierra.dependency :as dep]
            [clojure.edn :as edn]
            [clojure.walk :as walk]))

(defrecord Ref [key])

(defn ref [key]
  {:pre [(keyword? key)]}
  (->Ref key))

(defn ref? [x]
  (instance? Ref x))

(defn- find-refs [v]
  (cond
    (ref? v)  (list (:key v))
    (coll? v) (mapcat find-refs v)))

(defn dependencies [m]
  (reduce-kv (fn [g k v] (reduce #(dep/depend %1 k %2) g (find-refs v)))
             (dep/graph)
             m))

(defn read-string [s]
  (edn/read-string {:readers {'ref ref}} s))

(defn expand
  ([m]
   (reduce expand m (keys m)))
  ([m k]
   (let [v (m k)]
     (walk/postwalk #(if (and (ref? %) (= k (:key %))) v %) m))))

(defmulti start (fn [k v] k))

(defmethod start :default [_ v] v)

(defn- run-key [m k]
  (-> m
      (update k #(start k %))
      (expand k)))

(defn- sort-keys [ks m]
  (sort (dep/topo-comparator (dependencies m)) ks))

(defn run
  ([m]
   (run m (keys m)))
  ([m ks]
   (reduce run-key m (sort-keys ks m))))

(defmulti stop (fn [k v] k))

(defmethod stop :default [_ v] v)

(defn- halt-key [m k]
  (update m k #(stop k %)))

(defn halt
  ([m]
   (halt m (keys m)))
  ([m ks]
   (reduce halt-key m (reverse (sort-keys ks m)))))
