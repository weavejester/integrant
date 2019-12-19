(ns integrant.core
  (:refer-clojure :exclude [ref read-string run!])
  (:require #?(:clj [clojure.edn :as edn])
            [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [weavejester.dependency :as dep]))

(defprotocol RefLike
  (ref-key [r] "Return the key of the reference."))

(defrecord Ref    [key] RefLike (ref-key [_] key))
(defrecord RefSet [key] RefLike (ref-key [_] key))

(defn- composite-key? [keys]
  (and (vector? keys) (every? qualified-keyword? keys)))

(defn valid-config-key?
  "Returns true if the key is a keyword or valid composite key."
  [key]
  (or (qualified-keyword? key) (composite-key? key)))

(defn ref
  "Create a reference to a top-level key in a config map."
  [key]
  {:pre [(valid-config-key? key)]}
  (->Ref key))

(defn refset
  "Create a set of references to all matching top-level keys in a config map."
  [key]
  {:pre [(valid-config-key? key)]}
  (->RefSet key))

(defn ref?
  "Return true if its argument is a ref."
  [x]
  (instance? Ref x))

(defn refset?
  "Return true if its argument is a refset."
  [x]
  (instance? RefSet x))

(defn reflike?
  "Return true if its argument is a ref or a refset."
  [x]
  (satisfies? RefLike x))

(defn- depth-search [pred? coll]
  (filter pred? (tree-seq coll? seq coll)))

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
                (str/join ", " matching-keys))
           {:reason ::ambiguous-key
            :config config
            :key    key
            :matching-keys matching-keys}))

(defn derived-from?
  "Return true if a key is derived from candidate keyword or vector of
  keywords."
  [key candidate]
  (let [key (normalize-key key)]
    (if (vector? candidate)
      (every? #(isa? key %) candidate)
      (isa? key candidate))))

(defn find-derived
  "Return a seq of all entries in a map, m, where the key is derived from the
  a candidate key, k. If there are no matching keys, nil is returned. The
  candidate key may be a keyword, or vector of keywords."
  [m k]
  (seq (filter #(or (= (key %) k) (derived-from? (key %) k)) m)))

(defn find-derived-1
  "Return the map entry in a map, m, where the key is derived from the keyword,
  k. If there are no matching keys, nil is returned. If there is more than one
  matching key, an ambiguous key exception is raised."
  [m k]
  (let [kvs (find-derived m k)]
    (when (next kvs)
      (throw (ambiguous-key-exception m k (map key kvs))))
    (first kvs)))

(defn- find-derived-refs [config v include-refsets?]
  (->> (depth-search (if include-refsets? reflike? ref?) v)
       (map ref-key)
       (mapcat #(map key (find-derived config %)))))

(defn dependency-graph
  "Return a dependency graph of all the refs and refsets in a config. Resolves
  derived dependencies. Takes the following options:

  `:include-refsets?`
  : whether to include refsets in the dependency graph (defaults to true)"
  ([config]
   (dependency-graph config {}))
  ([config {:keys [include-refsets?] :or {include-refsets? true}}]
   (letfn [(find-refs [v]
             (find-derived-refs config v include-refsets?))]
     (reduce-kv (fn [g k v] (reduce #(dep/depend %1 k %2) g (find-refs v)))
                (dep/graph)
                config))))

(defn key-comparator
  "Create a key comparator from the dependency graph of a configuration map.
  The comparator is deterministic; it will always result in the same key
  order."
  [graph]
  (dep/topo-comparator #(compare (str %1) (str %2)) graph))

(defn- find-keys [config keys f]
  (let [graph  (dependency-graph config {:include-refsets? false})
        keyset (set (mapcat #(map key (find-derived config %)) keys))]
    (->> (f graph keyset)
         (set/union keyset)
         (sort (key-comparator (dependency-graph config))))))

(defn- dependent-keys [config keys]
  (find-keys config keys dep/transitive-dependencies-set))

(defn- reverse-dependent-keys [config keys]
  (reverse (find-keys config keys dep/transitive-dependents-set)))

#?(:clj
   (def ^:private default-readers {'ig/ref ref, 'ig/refset refset}))

#?(:clj
   (defn read-string
    "Read a config from a string of edn. Refs may be denotied by tagging keywords
     with #ig/ref."
     ([s]
      (read-string {:eof nil} s))
     ([opts s]
      (let [readers (merge default-readers (:readers opts {}))]
        (edn/read-string (assoc opts :readers readers) s)))))

#?(:clj
   (defn- keyword->namespaces [kw]
     (if-let [ns (namespace kw)]
       [(symbol ns)
        (symbol (str ns "." (name kw)))])))

#?(:clj
   (defn- key->namespaces [k]
     (if (vector? k)
       (mapcat keyword->namespaces k)
       (keyword->namespaces k))))

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
     ([config]
      (load-namespaces config (keys config)))
     ([config keys]
      (doall (->> (dependent-keys config keys)
                  (mapcat #(conj (ancestors %) %))
                  (mapcat key->namespaces)
                  (distinct)
                  (keep try-require))))))

(defn- missing-refs-exception [config refs]
  (ex-info (str "Missing definitions for refs: " (str/join ", " refs))
           {:reason ::missing-refs
            :config config
            :missing-refs refs}))

(defn- ambiguous-refs [config]
  (->> (depth-search ref? config)
       (map ref-key)
       (filter #(next (find-derived config %)))))

(defn- missing-refs [config]
  (->> (depth-search ref? config)
       (map ref-key)
       (remove #(find-derived config %))))

(defn- invalid-composite-keys [config]
  (->> (keys config) (filter vector?) (remove composite-key?)))

(defn- invalid-composite-key-exception [config key]
  (ex-info (str "Invalid composite key: " key ". Every keyword must be namespaced.")
           {:reason ::invalid-composite-key
            :config config
            :key key}))

(defn- resolve-ref [config resolvef ref]
  (let [[k v] (first (find-derived config (ref-key ref)))]
    (resolvef k v)))

(defn- resolve-refset [config resolvef refset]
  (set (for [[k v] (find-derived config (ref-key refset))]
         (resolvef k v))))

(defn- expand-key [config resolvef value]
  (walk/postwalk
   #(cond
      (ref? %)    (resolve-ref config resolvef %)
      (refset? %) (resolve-refset config resolvef %)
      :else       %)
   value))

(defn- run-exception [system completed remaining f k v t]
  (ex-info (str "Error on key " k " when running system")
           {:reason ::run-threw-exception
            :system system
            :completed-keys (reverse completed)
            :remaining-keys (rest remaining)
            :function f
            :key   k
            :value v}
           t))

(defn- try-run-action [system completed remaining f k]
  (let [v (system k)]
    (try (f k v)
         (catch #?(:clj Throwable :cljs :default) t
           (throw (run-exception system completed remaining f k v t))))))

(defn- run-loop [system keys f]
  (loop [completed (), remaining keys]
    (when (seq remaining)
      (let [k (first remaining)]
        (try-run-action system completed remaining f k)
        (recur (cons k completed) (rest remaining))))))

(defn- system-origin [system]
  (-> system meta ::origin (select-keys (keys system))))

(defn run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (run-loop system (dependent-keys (system-origin system) keys) f))

(defn reverse-run!
  "Apply a side-effectful function f to each key value pair in a system map.
  Keys are traversed in reverse dependency order. The function should take two
  arguments, a key and value."
  [system keys f]
  {:pre [(map? system) (some-> system meta ::origin)]}
  (run-loop system (reverse-dependent-keys (system-origin system) keys) f))

(defn fold
  "Reduce all the key value pairs in system map in dependency order, starting
  from an initial value. The function should take three arguments: the
  accumulator, the current key and the current value."
  [system f val]
  (let [graph (dependency-graph (system-origin system))]
    (->> (keys system)
         (sort (key-comparator graph))
         (reduce #(f %1 %2 (system %2)) val))))

(defn- build-exception [system f k v t]
  (ex-info (str "Error on key " k " when building system")
           {:reason   ::build-threw-exception
            :system   system
            :function f
            :key      k
            :value    v}
           t))

(defn- try-build-action [system f k v]
  (try (f k v)
       (catch #?(:clj Throwable :cljs :default) t
         (throw (build-exception system f k v t)))))

(defn- build-key [f assertf resolvef system [k v]]
  (let [v' (expand-key system resolvef v)]
    (assertf system k v')
    (-> system
        (assoc k (try-build-action system f k v'))
        (vary-meta assoc-in [::build k] v'))))

(defn build
  "Apply a function f to each key value pair in a configuration map. Keys are
  traversed in dependency order, and any references in the value expanded. The
  function should take two arguments, a key and value, and return a new value.
  An optional fourth argument, assertf, may be supplied to provide an assertion
  check on the system, key and expanded value."
  ([config keys f]
   (build config keys f (fn [_ _ _])))
  ([config keys f assertf]
   (build config keys f assertf (fn [_ v] v)))
  ([config keys f assertf resolvef]
   {:pre [(map? config)]}
   (let [relevant-keys   (dependent-keys config keys)
         relevant-config (select-keys config relevant-keys)]
     (when-let [invalid-key (first (invalid-composite-keys config))]
       (throw (invalid-composite-key-exception config invalid-key)))
     (when-let [ref (first (ambiguous-refs relevant-config))]
       (throw (ambiguous-key-exception config ref (map key (find-derived config ref)))))
     (when-let [refs (seq (missing-refs relevant-config))]
       (throw (missing-refs-exception config refs)))
     (reduce (partial build-key f assertf resolvef)
             (with-meta {} {::origin config})
             (map (fn [k] [k (config k)]) relevant-keys)))))

(defmulti resolve-key
  "Return a value to substitute for a reference prior to initiation. By default
  the value of the key is returned unaltered. This can be used to hide
  information that is only necessary to halt or suspend the key."
  {:arglists '([key value])}
  (fn [key value] (normalize-key key)))

(defmethod resolve-key :default [_ v] v)

(defn expand
  "Replace all refs with the values they correspond to."
  [config]
  (build config (keys config) (fn [_ v] v) (fn [_ _ _]) resolve-key))

(defmulti prep-key
  "Prepare the configuration associated with a key for initiation. This is
  generally used to add in default values and references. By default the
  method returns the value unaltered."
  {:arglists '([key value])}
  (fn [key value] (normalize-key key)))

(defmethod prep-key :default [_ v] v)

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

(defmulti pre-init-spec
  "Return a spec for the supplied key that is used to check the associated
  value before the key is initiated."
  normalize-key)

(defmethod pre-init-spec :default [_] nil)

(defn- spec-exception [system k v spec ed]
  (ex-info (str "Spec failed on key " k " when building system\n"
                (with-out-str (s/explain-out ed)))
           {:reason   ::build-failed-spec
            :system   system
            :key      k
            :value    v
            :spec     spec
            :explain  ed}))

(defmulti assert-pre-init-spec
  "Function that is used to assert that the data used to initialize the key matches the
  data returned by `pre-init-spec`.

  Defaults to asserting with `spec` but is configurable to allow alternative schema engines."
  {:arglists '([system key value])}
  (fn [_ _ _] :default))

(defmethod assert-pre-init-spec :default [system key value]
  (when-let [spec (pre-init-spec key)]
    (when-not (s/valid? spec value)
      (throw (spec-exception system key value spec (s/explain-data spec value))))))

(defn prep
  "Prepare a config map for initiation. The prep-key method is applied to each
  entry in the map, and the values replaced with the return value. This is used
  for adding default values and references to the configuration."
  ([config]
   (prep config (keys config)))
  ([config keys]
   {:pre [(map? config)]}
   (let [keyset (set keys)]
     (reduce-kv (fn [m k v] (assoc m k (if (keyset k) (prep-key k v) v))) {} config))))

(defn init
  "Turn a config map into an system map. Keys are traversed in dependency
  order, initiated via the init-key multimethod, then the refs associated with
  the key are expanded."
  ([config]
   (init config (keys config)))
  ([config keys]
   {:pre [(map? config)]}
   (build config keys init-key assert-pre-init-spec resolve-key)))

(defn halt!
  "Halt a system map by applying halt-key! in reverse dependency order."
  ([system]
   (halt! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys halt-key!)))

(defn- missing-keys [system ks]
  (remove (set ks) (keys system)))

(defn- halt-missing-keys! [config system keys]
  (let [graph        (-> system meta ::origin dependency-graph)
        missing-keys (missing-keys system (dependent-keys config keys))]
    (doseq [k (sort (key-comparator graph) missing-keys)]
      (halt-key! k (system k)))))

(defn resume
  "Turn a config map into a system map, reusing resources from an existing
  system when it's possible to do so. Keys are traversed in dependency order,
  resumed with the resume-key multimethod, then the refs associated with the
  key are expanded."
  ([config system]
   (resume config system (keys config)))
  ([config system keys]
   {:pre [(map? config) (map? system) (some-> system meta ::origin)]}
   (halt-missing-keys! config system keys)
   (build config keys
          (fn [k v]
            (if (contains? system k)
              (resume-key k v (-> system meta ::build (get k)) (system k))
              (init-key k v)))
          assert-pre-init-spec
          resolve-key)))

(defn suspend!
  "Suspend a system map by applying suspend-key! in reverse dependency order."
  ([system]
   (suspend! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys suspend-key!)))
