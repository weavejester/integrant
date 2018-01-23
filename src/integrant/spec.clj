(ns integrant.spec

  "Utilities for clojure.spec
   
   As far as validation is concerned, the user can use ::configuration. Given a configuration map,
   this spec validates every key-value by using the key to find a spec in the global registry.
   It takes into account derived and composite keys. It also checks if there are any ambiguous keys."

  (:refer-clojure :exclude [ref])
  (:require [clojure.spec.alpha :as s]
            [integrant.core     :as ig]))




;;;;;;;;;; API


(defn ref

  "Produces a spec for an integrant reference."

  [key]

  (s/and ig/reflike?
         #(ig/derived-from? (ig/ref-key %)
                            key)))




(defn get-spec

  "Finds the spec of a simple, derived or composite key."

  [key]

  (if (vector? key)
    (some s/get-spec
          (reverse key))
    (or (s/get-spec key)
        (some s/get-spec
              (parents key)))))




(defn unambiguous-refs?

  "Given `config-map`, are all the refs in `value` unambiguous ?"

  [config-map value]

  (not (some (fn ambiguous? [ref]
               (next (ig/find-derived config-map
                                      (ig/ref-key ref))))
             (filter ig/ref?
                     (tree-seq coll?
                               seq
                               value)))))




;;;;;;;;;; Private


(defn- -always-true

  "Always returns true."

  [_]

  true)




(defn- -generated

  "For multi-specs, always returns the generated values unchanged."

  [generated _tag]

  generated)




(defmulti ^:private -key-value

  "Produces a spec for [key value]."

  first

  :default ::default)




(defmethod -key-value ::default

  [[k]]

  (s/tuple -always-true
           (or (get-spec k)
               any?)))




(defmulti ^:private -unambiguous-refs

  "Given a configuration map, returns a spec testing of the refs in each value of a map are unambiguous."

  identity

  :default ::default)




(defmethod -unambiguous-refs ::default

  [config-map]

  (s/map-of -always-true
            #(unambiguous-refs? config-map
                                %)))




;;;;;;;;;; Specs


(s/def ::key-value

  (s/multi-spec -key-value
                -generated))


(s/def ::key-values

  (s/coll-of ::key-value))


(s/def ::unambiguous-refs

  (s/multi-spec -unambiguous-refs
                -generated))


(s/def ::configuration

  (s/and map?
         ::key-values
         ::unambiguous-refs))
