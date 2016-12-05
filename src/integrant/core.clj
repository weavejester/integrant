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
  [s]
  (edn/read-string {:readers {'ref ref}} s))

(defn expand
  "Replace all refs with the values they correspond to."
  ([m]
   (reduce expand m (keys m)))
  ([m k]
   (let [v (m k)]
     (walk/postwalk #(if (and (ref? %) (= k (:key %))) v %) m))))

(defmulti run-key
  "Turn a config value associated with a key into a running implementation. For
  example, a database URL might be turned into a database connection."
  (fn [k v] k))

(defmethod run-key :default [_ v] v)

(defmulti halt-key!
  "Halt a running implementation associated with a key. This is often used for
  stopping processes or cleaning up resources. For example, a database
  connection might be closed. The return value of this multimethod is
  discarded."
  (fn [k v] k))

(defmethod halt-key! :default [_ v])

(defn- sort-keys [ks m]
  (sort (dep/topo-comparator (dependency-graph m)) ks))

(defn- update-key [m k]
  (-> m (update k (partial run-key k)) (expand k)))

(defn run
  "Turn a config map into an implementation map. Keys are run via run-key in
  dependency order, then the refs associated with the key are expanded."
  ([config]
   (run config (keys config)))
  ([config keys]
   {:pre [(map? m)]}
   (when-let [refs (seq (missing-refs config))]
     (throw (ex-info (str "Missing definitions for refs: " (str/join ", " refs))
                     {:reason ::missing-refs
                      :config config
                      :missing-refs refs})))
   (-> (reduce update-key config (sort-keys keys config))
       (with-meta {::origin config}))))

(defn running?
  "Return true if its argument is a running implementation map."
  [impl]
  (contains? (meta impl) ::origin))

(defn halt!
  "Halt an implementation map by applying halt-key! in dependency order."
  ([impl]
   (halt! impl (keys impl)))
  ([impl keys]
   {:pre [(map? impl) (running? impl)]}
   (doseq [k (reverse (sort-keys keys (-> impl meta ::origin)))]
     (halt-key! k (impl k)))))
