(ns integrant.core
  (:refer-clojure :exclude [ref read-string run!])
  (:require [com.stuartsierra.dependency :as dep]
    #?(:clj [clojure.edn :as edn])
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

#?(:clj
   (defn read-string
    "Read a config from a string of edn. Refs may be denotied by tagging keywords
     with #ref."
     ([s]
      (read-string {:eof nil} s))
     ([opts s]
      (let [readers (merge {'ref ref} (:readers opts {}))]
        (edn/read-string (assoc opts :readers readers) s)))))

(defn- expand-key [config value]
  (walk/postwalk #(if (ref? %) (config (:key %)) %) value))

(defn- sort-keys [ks m]
  (sort (dep/topo-comparator (dependency-graph m)) ks))

(defn run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (doseq [k (sort-keys keys (-> system meta ::origin))]
    (f k (system k))))

(defn reverse-run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in reverse dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (doseq [k (reverse (sort-keys keys (-> system meta ::origin)))]
    (f k (system k))))

(defn- update-key [m k f]
  (update m k (comp (partial f k) (partial expand-key m))))

(defn build
  "Apply a function f to each key value pair in a configuration map. Keys are
  traversed in dependency order, and any references in the value expanded. The
  function should take two arguments, a key and value, and return a new value."
  [config keys f]
  {:pre [(map? config)]}
  (when-let [refs (seq (missing-refs config))]
    (throw (ex-info (str "Missing definitions for refs: " (str/join ", " refs))
                    {:reason ::missing-refs
                     :config config
                     :missing-refs refs})))
  (-> (reduce (fn [m k] (update-key m k f))
              config
              (sort-keys keys config))
      (with-meta {::origin config})))

(defn expand
  "Replace all refs with the values they correspond to."
  [config]
  (build config (keys config) (fn [_ v] v)))

(defmulti init-key
  "Turn a config value associated with a key into a concrete implementation.
  For example, a database URL might be turned into a database connection."
  (fn [key value] key))

(defmethod init-key :default [_ v] v)

(defmulti halt-key!
  "Halt a running implementation associated with a key. This is often used for
  stopping processes or cleaning up resources. For example, a database
  connection might be closed. The return value of this multimethod is
  discarded."
  (fn [key value] key))

(defmethod halt-key! :default [_ v])

(defmulti resume-key
  "Turn a config value associated with a key into a concrete implementation,
  but reuse resources (e.g. connections, running threads, etc) from an existing
  implementation. By default this multimethod calls init-key and ignores the
  additional argument."
  (fn [key config-value system-value] key))

(defmethod resume-key :default [k v _]
  (init-key k v))

(defmulti suspend-key!
  "Suspend a running implementation associated with a key, so that it may be
  eventually passed to resume-key. By default this multimethod calls halt-key!,
  but it may be customized to do things like keep a server running, but buffer
  incoming requests until the server is resumed."
  (fn [key value] key))

(defmethod suspend-key! :default [k v _]
  (halt-key! k v))

(defn init
  "Turn a config map into an system map. Keys are traversed in dependency
  order, initiated via the init-key multimethod, then the refs associated with
  the key are expanded."
  ([config]
   (init config (keys config)))
  ([config keys]
   {:pre [(map? config)]}
   (build config keys init-key)))

(defn halt!
  "Halt a system map by applying halt-key! in reverse dependency order."
  ([system]
   (halt! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys halt-key!)))

(defn resume
  "Turn a config map into a system map, reusing resources from an existing
  system when it's possible to do so. Keys are traversed in dependency order,
  resumed with the resume-key multimethod, then the refs associated with the
  key are expanded."
  ([config old-system]
   (resume config old-system (keys config)))
  ([config old-system keys]
   {:pre [(map? config) (map? old-system) (some-> old-system meta ::origin)]}
   (build config keys (fn [k v] (resume-key k v (old-system k))))))

(defn suspend!
  "Suspend a system map by applying suspend-key! in reverse dependency order."
  ([system]
   (suspend! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys suspend-key!)))
