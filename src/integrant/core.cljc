(ns integrant.core
  (:refer-clojure :exclude [ref read-string run!])
  (:require [com.stuartsierra.dependency :as dep]
    #?(:clj [clojure.edn :as edn])
            [clojure.walk :as walk]
            [clojure.set :as set]
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

(defonce
  ^{:doc "Return a unique keyword that is derived from an ordered collection of
  keywords. The function will return the same keyword for the same collection."
    :arglists '([kws])}
  composite-keyword
  (memoize
   (fn [kws]
     (let [parts     (for [kw kws] (str (namespace kw) "." (name kw)))
           prefix    (str (str/join "+" parts) "_")
           composite (keyword "integrant.composite" (str (gensym prefix)))]
       (doseq [kw kws] (derive composite kw))
       composite))))

(defn- normalize-key [k]
  (if (vector? k) (composite-keyword k) k))

(defn- ambiguous-key-exception [config key matching-keys]
  (ex-info (str "Ambiguous key: " key ". Found multiple candidates: "
                (str/join ", " (sort matching-keys)))
           {:reason ::ambiguous-key
            :config config
            :key    key
            :matching-keys matching-keys}))

(defn find-derived
  "Return a seq of all entries in a map, m, where the key is derived from the
  keyword, k. If there are no matching keys, nil is returned."
  [m k]
  (seq (filter #(or (= (key %) k) (isa? (normalize-key (key %)) k)) m)))

(defn find-derived-1
  "Return the map entry in a map, m, where the key is derived from the keyword,
  k. If there are no matching keys, nil is returned. If there is more than one
  matching key, an ambiguous key exception is raised."
  [m k]
  (let [kvs (find-derived m k)]
    (when (next kvs)
      (throw (ambiguous-key-exception m k (map key kvs))))
    (first kvs)))

(defn- find-derived-refs [config v]
  (mapcat #(map key (find-derived config %)) (find-refs v)))

(defn dependency-graph
  "Return a dependency graph of all the refs in a config. Resolve derived
  dependencies."
  [config]
  (reduce-kv (fn [g k v] (reduce #(dep/depend %1 k %2) g (find-derived-refs config v)))
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

#?(:clj 
   (defn- keyword->namespaces [kw]
     (let [kw (if (vector? kw) (first kw) kw)]
       (if-let [ns (namespace kw)]
         [(symbol ns)
          (symbol (str ns "." (name kw)))]))))

#?(:clj
   (defn- try-require [sym]
     (try (do (require sym) sym)
          (catch java.io.FileNotFoundException _))))

#?(:clj
   (defn load-namespaces
     "Attempt to load the namespaces referenced by the keys in a configuration.
     If a key is namespaced, both the namespace and the namespace concatenated
     with the name will be tried. For example, if a key is :foo.bar/baz, then the
     function will attempt to load the namespaces foo.bar and foo.bar.baz. Upon
     completion, a list of all loaded namespaces will be returned."
     [config]
     (doall (->> (keys config)
                 (mapcat keyword->namespaces)
                 (distinct)
                 (keep try-require)))))

(defn- missing-refs-exception [config refs]
  (ex-info (str "Missing definitions for refs: " (str/join ", " (sort refs)))
           {:reason ::missing-refs
            :config config
            :missing-refs refs}))

(defn- ambiguous-refs [config]
  (filter #(next (find-derived config %)) (find-refs config)))

(defn- missing-refs [config]
  (remove #(find-derived config %) (find-refs config)))

(defn- resolve-ref [config ref]
  (val (first (find-derived config (:key ref)))))

(defn- expand-key [config value]
  (walk/postwalk #(if (ref? %) (resolve-ref config %) %) value))

(defn- find-keys [config keys f]
  (let [graph  (dependency-graph config)
        keyset (set (mapcat #(map key (find-derived config %)) keys))]
    (->> (f graph keyset)
         (set/union keyset)
         (sort (dep/topo-comparator graph)))))

(defn- dependent-keys [config keys]
  (find-keys config keys dep/transitive-dependencies-set))

(defn- reverse-dependent-keys [config keys]
  (reverse (find-keys config keys dep/transitive-dependents-set)))

(defn run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (doseq [k (dependent-keys (-> system meta ::origin) keys)]
    (f k (system k))))

(defn reverse-run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in reverse dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (doseq [k (reverse-dependent-keys (-> system meta ::origin) keys)]
    (f k (system k))))

(defn- build-key [f m k]
  (let [v (expand-key m (m k))]
    (-> m
        (assoc k (f k v))
        (vary-meta assoc-in [::build k] v))))

(defn build
  "Apply a function f to each key value pair in a configuration map. Keys are
  traversed in dependency order, and any references in the value expanded. The
  function should take two arguments, a key and value, and return a new value."
  [config keys f]
  {:pre [(map? config)]}
  (let [relevant-keys   (dependent-keys config keys)
        relevant-config (select-keys config relevant-keys)]
    (when-let [ref (first (ambiguous-refs relevant-config))]
      (throw (ambiguous-key-exception config ref (map key (find-derived config ref)))))
    (when-let [refs (seq (missing-refs relevant-config))]
      (throw (missing-refs-exception config refs)))
    (-> (reduce (partial build-key f) relevant-config relevant-keys)
        (vary-meta assoc ::origin config))))

(defn expand
  "Replace all refs with the values they correspond to."
  [config]
  (build config (keys config) (fn [_ v] v)))

(defmulti init-key
  "Turn a config value associated with a key into a concrete implementation.
  For example, a database URL might be turned into a database connection."
  {:arglists '([key value])}
  (fn [key value] (normalize-key key)))

(defmulti halt-key!
  "Halt a running or suspended implementation associated with a key. This is
  often used for stopping processes or cleaning up resources. For example, a
  database connection might be closed. This multimethod must be idempotent.
  The return value of this multimethod is discarded."
  {:arglists '([key value])}
  (fn [key value] (normalize-key key)))

(defmethod halt-key! :default [_ v])

(defmulti resume-key
  "Turn a config value associated with a key into a concrete implementation,
  but reuse resources (e.g. connections, running threads, etc) from an existing
  implementation. By default this multimethod calls init-key and ignores the
  additional argument."
  {:arglists '([key value old-value old-impl])}
  (fn [key value old-value old-impl] (normalize-key key)))

(defmethod resume-key :default [k v _ _]
  (init-key k v))

(defmulti suspend-key!
  "Suspend a running implementation associated with a key, so that it may be
  eventually passed to resume-key. By default this multimethod calls halt-key!,
  but it may be customized to do things like keep a server running, but buffer
  incoming requests until the server is resumed."
  {:arglists '([key value])}
  (fn [key value] (normalize-key key)))

(defmethod suspend-key! :default [k v]
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

(defn- missing-keys [system ks]
  (remove (set ks) (keys system)))

(defn resume
  "Turn a config map into a system map, reusing resources from an existing
  system when it's possible to do so. Keys are traversed in dependency order,
  resumed with the resume-key multimethod, then the refs associated with the
  key are expanded."
  ([config system]
   (resume config system (keys config)))
  ([config system keys]
   {:pre [(map? config) (map? system) (some-> system meta ::origin)]}
   (halt! system (missing-keys system keys))
   (build config keys (fn [k v]
                        (if (contains? system k)
                          (resume-key k v (-> system meta ::build (get k)) (system k))
                          (init-key k v))))))

(defn suspend!
  "Suspend a system map by applying suspend-key! in reverse dependency order."
  ([system]
   (suspend! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys suspend-key!)))
