(ns integrant.core
  (:refer-clojure :exclude [ref read-string])
  (:require [com.stuartsierra.dependency :as dep]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(defrecord Ref [key])

(defn ref
  "Create a reference to a top-level key in a config map."
  [key]
  {:pre [(keyword? key)]}
  (->Ref key))

(defn ref?
  "Return true if its argument is a ref."
  [x]
  (instance? Ref x))

(defn- find-refs [v]
  (cond
    (ref? v)  (list (:key v))
    (coll? v) (mapcat find-refs v)))

(defn- missing-refs [config]
  (remove (-> config keys set) (find-refs config)))

(defn dependency-graph
  "Return a dependency graph of all the refs in a config."
  [config]
  (reduce-kv (fn [g k v] (reduce #(dep/depend %1 k %2) g (find-refs v)))
             (dep/graph)
             config))

(defn read-string
  "Read a config from a string of edn. Refs may be denotied by tagging keywords
  with #ref."
  ([s]
   (read-string {:eof nil} s))
  ([opts s]
   (let [readers (merge {'ref ref} (:readers opts {}))]
     (edn/read-string (assoc opts :readers readers) s))))

(defn- expand-key [config value]
  (walk/postwalk #(if (ref? %) (config (:key %)) %) value))

(defn- sort-keys [ks m]
  (sort (dep/topo-comparator (dependency-graph m)) ks))

(defn expand
  "Replace all refs with the values they correspond to."
  [config]
  (reduce (fn [m k] (update m k (partial expand-key m)))
          config
          (sort-keys (keys config) config)))

(defmulti init-key
  "Turn a config value associated with a key into a concrete implementation.
  For example, a database URL might be turned into a database connection."
  (fn [k v] k))

(defmethod init-key :default [_ v] v)

(defmulti halt-key!
  "Halt a running implementation associated with a key. This is often used for
  stopping processes or cleaning up resources. For example, a database
  connection might be closed. The return value of this multimethod is
  discarded."
  (fn [k v] k))

(defmethod halt-key! :default [_ v])

(defn- update-key [m k]
  (update m k (comp (partial init-key k) (partial expand-key m))))

(defn init
  "Turn a config map into an system map. Keys are traversed in dependency
  order, initiated via the init-key multimethod, then the refs associated with
  the key are expanded."
  ([config]
   (init config (keys config)))
  ([config keys]
   {:pre [(map? config)]}
   (when-let [refs (seq (missing-refs config))]
     (throw (ex-info (str "Missing definitions for refs: " (str/join ", " refs))
                     {:reason ::missing-refs
                      :config config
                      :missing-refs refs})))
   (-> (reduce update-key config (sort-keys keys config))
       (with-meta {::origin config}))))

(defn halt!
  "Halt an system map by applying halt-key! in reverse dependency order."
  ([system]
   (halt! system (keys system)))
  ([system keys]
   {:pre [(map? system) (-> system meta ::origin)]}
   (doseq [k (reverse (sort-keys keys (-> system meta ::origin)))]
     (halt-key! k (system k)))))
