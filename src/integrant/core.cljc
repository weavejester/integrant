(ns integrant.core
  (:refer-clojure :exclude [ref read-string run! var? #?@(:cljs [Var ->Var])])
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [clojure.tools.reader.edn :as edn])
            [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.string :as str]
            [weavejester.dependency :as dep]))

(def ^:private registry (atom {}))

(defn annotate
  "Annotate a namespaced keyword with a map of metadata that will be stored in a
  global registry. Use [[describe]] to retrieve the keyword annotation map."
  [kw metadata]
  {:pre [(qualified-keyword? kw) (map? metadata)]}
  (swap! registry assoc kw metadata))

(defn describe
  "Return the annotation map for a namespaced keyword."
  [kw]
  {:pre [(qualified-keyword? kw)]}
  (@registry kw))

(defprotocol RefLike
  (ref-key [r] "Return the key of the reference.")
  (ref-resolve [r config resolvef] "Return the resolved value."))

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

(defn- invalid-ref-exception [ref]
  (ex-info (str "Invalid reference: " ref ". Must be a qualified keyword or a "
                "vector of qualified keywords.")
           {:reason ::invalid-ref, :ref ref}))

(defn- composite-key? [keys]
  (and (vector? keys) (every? qualified-keyword? keys)))

(defn valid-config-key?
  "Return true if the key is a keyword or valid composite key."
  [key]
  (or (qualified-keyword? key) (composite-key? key)))

(defn normalize-key
  "Given a valid Integrant key, return a keyword that uniquely identifies it."
  [key]
  {:pre [(valid-config-key? key)]}
  (if (composite-key? key) (composite-keyword key) key))

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

(defrecord Ref [key]
  RefLike
  (ref-key [_] key)
  (ref-resolve [_ config resolvef]
    (let [[k v] (first (find-derived config key))]
      (resolvef k v))))

(defrecord RefSet [key]
  RefLike
  (ref-key [_] key)
  (ref-resolve [_ config resolvef]
    (set (for [[k v] (find-derived config key)]
           (resolvef k v)))))

(defn ref
  "Create a reference to a top-level key in a config map."
  [key]
  (when-not (valid-config-key? key)
    (throw (invalid-ref-exception key)))
  (->Ref key))

(defn refset
  "Create a set of references to all matching top-level keys in a config map."
  [key]
  (when-not (valid-config-key? key)
    (throw (invalid-ref-exception key)))
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

(defrecord Profile [])

(defn profile
  "Create a map of profile keys to values. See: [[deprofile]]."
  [& {:as m}]
  (map->Profile m))

(defn profile?
  "Return true if its argument is a profile."
  [x]
  (instance? Profile x))

(defrecord Var [name])

(defn var
  "Create a variable in a configuration that must be substituted for a value
  using [[bind]]."
  [name]
  {:pre [(symbol? name)]}
  (->Var name))

(defn var?
  "Return true if its argument is a var."
  [x]
  (instance? Var x))

(defn bind
  "Bind the variables (see: [[var]]) in a collection to values, based on a
  lookup map."
  [config lookup-map]
  {:pre [(map? config) (map? lookup-map)]}
  (walk/postwalk #(if (and (var? %) (contains? lookup-map (:name %)))
                    (lookup-map (:name %))
                    %)
                 config))

(defn- depth-search [pred? coll]
  (filter pred? (tree-seq coll? seq coll)))

(defn- ambiguous-key-exception [config key matching-keys]
  (ex-info (str "Ambiguous key: " key ". Found multiple candidates: "
                (str/join ", " matching-keys))
           {:reason ::ambiguous-key
            :config config
            :key    key
            :matching-keys matching-keys}))

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

(def ^:private default-readers
  {'ig/ref     ref
   'ig/refset  refset
   'ig/profile profile
   'ig/var     var})

(defn read-string
  "Read a config from a string of edn. Refs may be denotied by tagging keywords
  with `#ig/ref`."
  ([s]
   (read-string {:eof nil} s))
  ([opts s]
   (let [readers (merge default-readers (:readers opts {}))]
     (edn/read-string (assoc opts :readers readers) s))))

#?(:clj
   (defn- keyword->namespaces [kw]
     (when-let [ns (namespace kw)]
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
     with the name will be tried. For example, if a key is `:foo.bar/baz`, then
     the function will attempt to load the namespaces `foo.bar` and
     `foo.bar.baz`. Upon completion, a list of all loaded namespaces will be
     returned."
     ([config]
      (load-namespaces config (keys config)))
     ([config keys]
      (doall (->> (dependent-keys config keys)
                  (mapcat #(conj (ancestors %) %))
                  (mapcat key->namespaces)
                  (distinct)
                  (keep try-require))))))

#?(:clj
   (defn- resources [path]
     (let [cl (clojure.lang.RT/baseLoader)]
       (enumeration-seq (.getResources cl path)))))

#?(:clj
   (defn load-hierarchy
     "Search the base classpath for all resources that share the same
     path (by default `integrant/hierarchy.edn`), and use their contents
     to extend the global `derive` hierarchy. This allows a hierarchy to be
     constructed without needing to load every namespace.

     The hierarchy resources should be edn files that map child keywords
     to vectors of parents. For example:

         {:example/child [:example/father :example/mother]}

     This is equivalent:

         (derive :example/child :example/father)
         (derive :example/child :example/mother)"
     ([] (load-hierarchy "integrant/hierarchy.edn"))
     ([path]
      (doseq [url (resources path)]
        (let [hierarchy (edn/read-string (slurp url))]
          (doseq [[tag parents] hierarchy, parent parents]
            (derive tag parent)))))))

#?(:clj
   (defn load-annotations
     "Search the base classpath for all resources that share the same path
     (by default `integrant/annotations.edn`), and use their contents to
     add annotations to namespaced keywords via [[annotate]]. This allows
     annotations to be specified without needing to load every namespace.

     The annoation resources should be edn files that map namespaced keywords
     to maps of annotation metadata. For example:

         {:example/keyword {:doc \"An example keyword.\"}}

     This is equivalent to:

         (annotate :example/keyword {:doc \"An example keyword.\"})"
     ([] (load-annotations "integrant/annotations.edn"))
     ([path]
      (doseq [url (resources path)]
        (let [annotations (edn/read-string (slurp url))]
          (doseq [[kw metadata] annotations]
            (annotate kw metadata)))))))

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
  (ex-info (str "Invalid composite key: " key ". "
                "Every keyword must be namespaced.")
           {:reason ::invalid-composite-key
            :config config
            :key key}))

(defn- unbound-vars-exception [config vars]
  (ex-info (str "Unbound vars: " (str/join ", " vars))
           {:reason ::unbound-vars
            :config config
            :unbound-vars vars}))

(defn- unbound-vars [config]
  (map :name (depth-search var? config)))

(defn- resolve-refs [config resolvef value]
  (walk/postwalk
   #(if (reflike? %) (ref-resolve % config resolvef) %)
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
  (let [v' (resolve-refs system resolvef v)]
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
       (throw (ambiguous-key-exception config ref
                                       (map key (find-derived config ref)))))
     (when-let [refs (seq (missing-refs relevant-config))]
       (throw (missing-refs-exception config refs)))
     (when-let [vars (seq (unbound-vars relevant-config))]
       (throw (unbound-vars-exception config vars)))
     (reduce (partial build-key f assertf resolvef)
             (with-meta {} {::origin config})
             (map (fn [k] [k (config k)]) relevant-keys)))))

(defmulti resolve-key
  "Return a value to substitute for a reference prior to initiation. By default
  the value of the key is returned unaltered. This can be used to hide
  information that is only necessary to halt or suspend the key."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

(defmethod resolve-key :default [_ v] v)

(defmulti expand-key
  "Expand a config value into a map that is then merged back into the config.
  Defaults to returning a map `{key value}`. See: [[expand]]."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

(defmulti init-key
  "Turn a config value associated with a key into a concrete implementation.
  For example, a database URL might be turned into a database connection."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

#?(:clj
   (defmethod init-key :default [k v]
     (let [sym (symbol (namespace k) (name k))]
       (if-some [var (find-var sym)]
         ((var-get var) v)
         (throw (ex-info
                 (str "Unable to find an init-key method or function for " k)
                 {:reason ::missing-init-key
                  :key    k
                  :value  v}))))))

(defmulti halt-key!
  "Halt a running or suspended implementation associated with a key. This is
  often used for stopping processes or cleaning up resources. For example, a
  database connection might be closed. This multimethod must be idempotent.
  The return value of this multimethod is discarded."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

(defmethod halt-key! :default [_ _])

(defmulti resume-key
  "Turn a config value associated with a key into a concrete implementation,
  but reuse resources (e.g. connections, running threads, etc) from an existing
  implementation. By default this multimethod calls init-key and ignores the
  additional argument."
  {:arglists '([key value old-value old-impl])}
  (fn [key _value _old-value _old-impl] (normalize-key key)))

(defmethod resume-key :default [k v _ _]
  (init-key k v))

(defmulti suspend-key!
  "Suspend a running implementation associated with a key, so that it may be
  eventually passed to resume-key. By default this multimethod calls
  [[halt-key!]], but it may be customized to do things like keep a server
  running, but buffer incoming requests until the server is resumed."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

(defmethod suspend-key! :default [k v]
  (halt-key! k v))

(defmulti assert-key
  "Check that the value of a key is correct immediately before the key is
  initiated. If the value is incorrect, an appropriate exception should
  be thrown."
  {:arglists '([key value])}
  (fn [key _value] (normalize-key key)))

(defmethod assert-key :default [_ _])

(defn- wrap-assert-exception [ex system key value]
  (ex-info (str "Assertion failed on key " key " when building system")
           {:reason  ::build-failed-spec
            :system  system
            :key     key
            :value   value}
           ex))

(defn- wrapped-assert-key [system key value]
  (try (assert-key key value)
       (catch #?(:clj Throwable :cljs :default) err
         (throw (wrap-assert-exception err system key value)))))

(defn- missing-profile-key-exception [profile keys]
  (ex-info (str "Missing a valid key for profile: #ig/profile "
                (into {} profile))
           {:reason       ::missing-profile-key
            :profile      profile
            :profile-keys keys}))

(defn- deprofile-1 [profile keys]
  (if-some [key (first (filter #(contains? profile %) keys))]
    (get profile key)
    (throw (missing-profile-key-exception profile keys))))

(defn deprofile
  "Find all profiles in a collection, then turns them into values using an
  ordered collection of profile keys. The profile keys will be tried on each
  profile in order until the profile returns a match. If there is no match, a
  exception will be thrown."
  ([profile-keys]
   #(deprofile % profile-keys))
  ([coll profile-keys]
   (walk/postwalk #(if (profile? %) (deprofile-1 % profile-keys) %) coll)))

(defn- normal-map? [x]
  (and (map? x) (not (record? x))))

(defn- nested-values [idx [k v]]
  (if (and (normal-map? v) (seq v))
    (mapcat #(nested-values (conj idx k) %) v)
    (list {:index (conj idx k), :value v})))

(defn- converge-values [[k v]]
  (->> (mapcat #(nested-values [] %) v)
       (map #(assoc % :key k))))

(defn- converge-conflicts [converges overrides]
  (let [override-indexes (set (map :index overrides))]
    (->> converges
         (remove (comp override-indexes :index))
         (group-by :index)
         (vals)
         (filter #(->> % (map :value) set next)))))

(defn- converge-conflict-exception [config expansions]
  (let [index (-> expansions first :index)
        keys  (map :key expansions)]
    (ex-info (str "Conflicting values at index " index " when converging: "
                  (str/join ", " keys) ".")
             {:reason            ::conflicting-expands
              :config            config
              :conflicting-index index
              :expand-keys       keys})))

(defn- assoc-converge [m {:keys [index value]}]
  (if (or (not= value {}) (= ::missing (get-in m index ::missing)))
    (assoc-in m index value)
    m))

(defn converge
  "Deep-merge the values of a map. Raises an error on conflicting keys, unless
  an override is specified via the override-map."
  ([m] (converge m {}))
  ([m override-map]
   {:pre [(map? m) (every? map? (vals m))]}
   (let [converges (mapcat converge-values m)
         overrides (mapcat #(nested-values [] %) override-map)]
     (when-let [conflict (first (converge-conflicts converges overrides))]
       (throw (converge-conflict-exception m conflict)))
     (reduce assoc-converge {} (concat converges overrides)))))

(defn- can-expand-key? [k]
  (get-method expand-key (normalize-key k)))

(defn expand
  "Expand 'modules' in the config map prior to initiation. The [[expand-key]]
  method is applied to each entry in the map to create an expansion, and the
  results are deep-merged together using [[converge]] to produce a new
  configuration.

  If two expansions generate different values for the same keys, an exception
  will be raised. Configuration values that do not come from an expansion will
  override keys from expansions, allowing conflicts to be resolved by user-
  defined values.

  Additionally, an function, innerf, may be supplied to transform the output
  from expand-key before it is converged. A common use for this is to
  [[deprofile]] the expansions."
  ([config]
   (expand config identity))
  ([config innerf]
   (expand config innerf (keys config)))
  ([config innerf keys]
   {:pre [(map? config)]}
   (let [key-set     (set keys)
         expand-key? (fn [[k _]] (and (key-set k) (can-expand-key? k)))]
     (converge (->> (filter expand-key? config)
                    (map (fn [[k v]] [k (innerf (expand-key k v))]))
                    (reduce (fn [m [k v]] (assoc m k v)) {}))
               (->> (remove expand-key? config)
                    (into {}))))))

(defn init
  "Turn a config map into an system map. Keys are traversed in dependency
  order, initiated via the [[init-key]] multimethod, then the refs associated
  with the key are resolved."
  ([config]
   (init config (keys config)))
  ([config keys]
   {:pre [(map? config)]}
   (build config keys init-key wrapped-assert-key resolve-key)))

(defn halt!
  "Halt a system map by applying [[halt-key!]] in reverse dependency order."
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
  resumed with the [[resume-key]] multimethod, then the refs associated with
  the key are resolved."
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
          wrapped-assert-key
          resolve-key)))

(defn suspend!
  "Suspend a system map by applying [[suspend-key!]] in reverse dependency
  order."
  ([system]
   (suspend! system (keys system)))
  ([system keys]
   {:pre [(map? system) (some-> system meta ::origin)]}
   (reverse-run! system keys suspend-key!)))
