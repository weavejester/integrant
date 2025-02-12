# Integrant [![Build Status](https://github.com/weavejester/integrant/actions/workflows/test.yml/badge.svg)](https://github.com/weavejester/integrant/actions/workflows/test.yml)

> integrant /ˈɪntɪɡr(ə)nt/
>
> (of parts) making up or contributing to a whole; constituent.

Integrant is a Clojure (and ClojureScript) micro-framework for
building applications with data-driven architecture. It can be thought
of as an alternative to [Component][] or [Mount][], and was inspired
by [Arachne][] and through work on [Duct][].

[component]: https://github.com/stuartsierra/component
[mount]: https://github.com/tolitius/mount
[arachne]: http://arachne-framework.org/
[duct]: https://github.com/duct-framework/duct

## Rationale

Integrant was built as a reaction to fix some perceived weaknesses
with Component.

In Component, systems are created programmatically. Constructor
functions are used to build records, which are then assembled into
systems.

In Integrant, systems are created from a configuration data structure,
typically loaded from an [edn][] resource. The architecture of the
application is defined through data, rather than code.

In Component, only records or maps may have dependencies. Anything
else you might want to have dependencies, like a function, needs to be
wrapped in a record.

In Integrant, anything can be dependent on anything else. The
dependencies are resolved from the configuration before it's
initialized into a system.

[edn]: https://github.com/edn-format/edn

## Installation

Add the following dependency to your deps.edn file:

    integrant/integrant {:mvn/version "0.13.1"}

Or this to your Leiningen dependencies:

    [integrant "0.13.1"]

## Presentations

* [Enter Integrant](https://skillsmatter.com/skillscasts/9820-enter-integrant)

## Usage

### Configurations

Integrant starts with a configuration map. Each top-level key in the
map represents a configuration that can be "initialized" into a
concrete implementation. Configurations can reference other keys via
the `ref` (or `refset`) function.

For example:

```clojure
(require '[integrant.core :as ig])

(def config
  {:adapter/jetty {:port 8080, :handler (ig/ref :handler/greet)}
   :handler/greet {:name "Alice"}})
```

Alternatively, you can specify your configuration as pure edn:

```edn
{:adapter/jetty {:port 8080, :handler #ig/ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

And load it with Integrant's version of `read-string`:

```clojure
(def config
  (ig/read-string (slurp "config.edn")))
```

### Initializing and halting

Once you have a configuration, Integrant needs to be told how to
implement it. The `init-key` multimethod takes two arguments, a key
and its corresponding value, and tells Integrant how to initialize it:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[ring.util.response :as resp])

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (jetty/run-jetty handler (-> opts (dissoc :handler) (assoc :join? false))))

(defmethod ig/init-key :handler/greet [_ {:keys [name]}]
  (fn [_] (resp/response (str "Hello " name))))
```

Keys are initialized recursively, with the values in the map being
replaced by the return value from `init-key`.

In the configuration we defined before, `:handler/greet` will be
initialized first, and its value replaced with a handler function.
When `:adapter/jetty` references `:handler/greet`, it will receive the
initialized handler function, rather than the raw configuration.

The `halt-key!` multimethod tells Integrant how to stop and clean up
after a key. Like `init-key`, it takes two arguments, a key and its
corresponding initialized value.

```clojure
(defmethod ig/halt-key! :adapter/jetty [_ server]
  (.stop server))
```

Note that we don't need to define a `halt-key!` for `:handler/greet`.

Once the multimethods have been defined, we can use the `init` and
`halt!` functions to handle entire configurations. The `init` function
will start keys in dependency order, and resolve references as it
goes:

```clojure
(def system
  (ig/init config))
```

When a system needs to be shut down, `halt!` is used:

```clojure
(ig/halt! system)
```

Like Component, `halt!` shuts down the system in reverse dependency
order. Unlike Component, `halt!` is entirely side-effectful. The
return value should be ignored, and the system structure discarded.

It's also important that `halt-key!` is **idempotent**. We should be
able to run it multiple times on the same key without issue.

Integrant marks functions that are entirely side-effectful with an
ending `!`. You should ignore the return value of any function ending
in a `!`.

Both `init` and `halt!` can take a second argument of a collection of
keys. If this is supplied, the functions will only initiate or halt
the supplied keys (and any referenced keys). For example:

```clojure
(def system
  (ig/init config [:adapter/jetty]))
```

#### Initializer functions

Sometimes all that is necessary is `init-key`, particularly if what is
being initiated is all in memory, and we can rely on the garbage
collector to clean up afterwards.

For this purpose, `init-key` will try to find a function with the
**same namespace and name** as the keyword, if no more specific method
is set. For example:

```clojure
(def config
  {::sugared-greet {:name "Alice"}})

(defn sugared-greet [{:keys [name]}]
  (println "Hi" name))

(ig/init config)
```

The `sugared-greet` function is equivalent to the `init-key` method:

```clojure
(defmethod ig/init-key ::sugared-greet [_ {:keys [name]}]
  (println "Hi" name))
```

Note that the function needs to be in a loaded namespace for `init-key`
to find it. The `integrant.core/load-namespaces` function can be used on
a configuration to load namespaces matching the keys.


### Suspending and resuming

During development, we often want to rebuild a system, but not to
close open connections or terminate running threads. For this purpose
Integrant has the `suspend!` and `resume` functions.

The `suspend!` function acts like `halt!`:

```clojure
(ig/suspend! system)
```

By default this functions the same as `halt!`, but we can customize
the behavior with the `suspend-key!` multimethod to keep open
connections and resources that `halt-key!` would close.

Like `halt-key!`, `suspend-key!` should be both side-effectful and
idempotent.

The `resume` function acts like `init` but takes an additional
argument specifying a suspended system:

```clojure
(def new-system
  (ig/resume config system))
```

By default the system argument is ignored and `resume` functions the
same as `init`, but as with `suspend!` we can customize the behavior
with the `resume-key` multimethod. If we implement this method, we can
reuse open resources from the suspended system.

To illustrate this, let's reimplement the Jetty adapter with the
capability to suspend and resume:

```clojure
(defmethod ig/init-key :adapter/jetty [_ opts]
  (let [handler (atom (delay (:handler opts)))
        options (-> opts (dissoc :handler) (assoc :join? false))]
    {:handler handler
     :server  (jetty/run-jetty (fn [req] (@@handler req)) options)}))

(defmethod ig/halt-key! :adapter/jetty [_ {:keys [server]}]
  (.stop server))

(defmethod ig/suspend-key! :adapter/jetty [_ {:keys [handler]}]
  (reset! handler (promise)))

(defmethod ig/resume-key :adapter/jetty [key opts old-opts old-impl]
  (if (= (dissoc opts :handler) (dissoc old-opts :handler))
    (do (deliver @(:handler old-impl) (:handler opts))
        old-impl)
    (do (ig/halt-key! key old-impl)
        (ig/init-key key opts))))
```

This example may require some explanation. Instead of passing the
handler directly to the web server, we put it in an `atom`, so that we
can change the handler without restarting the server.

We further encase the handler in a `delay`. This allows us to replace
it with a `promise` when we suspend the server. Because a promise will
block until a value is delivered, once suspended the server will
accept requests but wait around until it's resumed.

Once we decide to resume the server, we first check to see if the
options have changed. If they have, we don't take any chances; better
to halt and re-init from scratch. If the server options haven't
changed, then deliver the new handler to the promise which unblocks
the server.

Note that we only need to go to this additional effort if retaining
open resources is useful during development, otherwise we can rely on
the default `init` and `halt!` behavior. In production, it's always
better to terminate and restart.

Like `init` and `halt!`, `resume` and `suspend!` can be supplied with
a collection of keys to narrow down the parts of the configuration
that are suspended or resumed.

### Resolving

It's sometimes useful to hide information when resolving a
reference. In our previous example, we changed the initiation from:

```clojure
(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (jetty/run-jetty handler (-> opts (dissoc :handler) (assoc :join? false))))
```

To:

```clojure
(defmethod ig/init-key :adapter/jetty [_ opts]
  (let [handler (atom (delay (:handler opts)))
        options (-> opts (dissoc :handler) (assoc :join? false))]
    {:handler handler
     :server  (jetty/run-jetty (fn [req] (@@handler req)) options)}))
```

This changed the return value from a Jetty server object to a map, so
that `suspend!` and `resume` would be able to temporarily block the
handler. However, this also changes the return type! Ideally, we'd want
to pass the handler atom to `suspend-key!` and `resume-key`, without
affecting how references are resolved in the configuration.

To solve this, we can use `resolve-key`:

```clojure
(defmethod ig/resolve-key :adapter/jetty [_ {:keys [server]}]
  server)
```

Before a reference is resolved, `resolve-key` is applied. This allows
us to cut out information that is only relevant behind the scenes. In
this case, we replace the map with the container Jetty server object.

### Expanding

Before being initiated, keys can be *expanded*. Expansions can be
thought of as the equivalent of macros in normal Clojure code.

An expansion is defined via the `expand-key` method. When `expand` is
called on a configuration map, `expand-key` is called on every key/value
that implements that method, and then the results are deep-merged into a
new configuration map.

Expansions are used to abstract groups of keys. For example:

```clojure
(defmethod ig/expand-key :module/greet [_ {:keys [name]}]
  {:adapter/jetty {:port 8080, :handler (ig/ref :handler/greet)}
   :handler/greet {:name name}})
```

This would be used in a configuration in the following way:

```edn
{:module/greet {:name "Alice"}}
```

And would expand to:

```edn
{:adapter/jetty {:port 8080, :handler #ig/ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

This allows for commonly used configurations to be factored out into
reusable modules, while still allowing the expansion to be inspected and
modified. For example:

```edn
{:module/greet {:name "Alice"}
 :adapter/jetty {:port 3000}}
```

The port set via `:adapter/jetty` will override the port set when
`:module/greet` is expanded:

```edn
{:adapter/jetty {:port 3000, :handler #ig/ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

This is because values you set via the configuration are considered to
be higher priority than those generated via expansions. This can also
be used to resolve conflicts in expansions. For example, suppose we have
an expansion:

```clojure
(defmethod ig/expand-key :module/web-server [_ _]
  {:adapter/jetty {:port 80}})
```

If we used this together with our `:module/greet` expansion, the port
number would conflict. `:module/web-server` would want it to be `80`,
while `:module/greet` would want it to be `8080`. Integrant can't
resolve this on its own (it will raise an exception), so we need to
specify which value it should use:

```edn
{:module/greet {:name "Alice"}
 :module/web-server {}
 :adapter/jetty {:port 80}}
```

To make use of expansions, the `integrant.core/expand` function needs to
be called before the configuration is initiated:

```clojure
(-> config ig/expand ig/init)
```

#### Replacing prep

Expansions supplant the now deprecated `prep` and `prep-key`. If you had a
method that looked like:

```clojure
(defmethod ig/prep-key ::example [_ v]
  (assoc v :example "example prep"))
```

This should be turned into an `expand-key` method like this:

```clojure
(defmethod ig/expand-key ::example [k v]
  {k (assoc v :example "example prep")})
```

### Profiles

Sometimes Integrant operates in multiple environments that require the
configuration to be slightly changed. This is where profiles can be
useful.

For example, suppose we wanted the web server to run on port 8080 in the
development environment, and port 80 in the production environment:

```edn
{:adapter/jetty {:port #ig/profile {:dev 8080, :prod 80}}}
```

To choose a profile to use, the `integrant.core/deprofile` function is
used:

```clojure
(ig/deprofile config [:dev])
```

This function takes the configuration and an ordered collection of
profile keys. The keys are tried in sequence until one fits. If there's
a profile that no supplied key fits, an exception is raised.

In the above case, it will return a configuration map:

```edn
{:adapter/jetty {:port 8080}}
```

#### Profiles and expansions

It's sometimes useful for expansions to be given profiles. For example:

```clojure
(defmethod ig/expand-key :module/greet [_ {:keys [name]}]
  (ig/profile
   :dev  {:adapter.jetty {:port 8080, :handler (ig/ref :handler/debug)}
          :handler/debug {:name name}}
   :prod {:adapter/jetty {:port 80, :handler (ig/ref :handler/greet)}
          :handler/greet {:name name}})
```

The expansion generated depends on the profile used. To use this, the
deprofile step needs to be carried out after the expansion, but before
the expansions are merged. Fortunately, the `expand` function allows for
a inner processing function to be supplied:

```clojure
(ig/expand config #(ig/deprofile % [:dev]))
```

This ensures the deprofile step is applied before the expansions are
merged. This can also be written more concisely as:

```clojure
(ig/expand config (ig/deprofile [:dev]))
```

## Vars

A var is a placeholder for a value that will be added later to the
configuration:

```edn
{:adapter/jetty {:port #ig/var port}}
```

This can be set by supplying a map of values to the `bind` function:

```clojure
(ig/bind config {'port 8080})
```

If there are any unbound vars when `init` is called, an exception will
be thrown.

### Derived keywords

Keywords have an inherited hierarchy. Integrant takes advantage of
this by allowing keywords to refer to their descendants. For example:

```clojure
(derive :adapter/jetty :adapter/ring)
```

This sets up a hierarchical relationship, where the specific
`:adapter/jetty` keyword is derived from the more generic
`:adapter/ring`.

We can now use `:adapter/ring` in place of `:adapter/jetty`:

```clojure
(ig/init config [:adapter/ring])
```

We can also use it as a reference, but only if the reference is
unambiguous, and only refers to one key in the configuration.

### Composite keys

Sometimes it's useful to have two keys of the same type in your
configuration. For example, you may want to run two Ring adapters on
different ports.

One way would be to create two new keywords, derived from a common
parent:

```clojure
(derive :example/web-1 :adapter/jetty)
(derive :example/web-2 :adapter/jetty)
```

You could then write a configuration like:

```edn
{:example/web-1 {:port 8080, :handler #ig/ref :handler/greet}
 :example/web-2 {:port 8081, :handler #ig/ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

However, you could also make use of composite keys. If your
configuration contains a key that is a vector of keywords, Integrant
treats it as being derived from all the keywords inside it.

So you could also write:

```edn
{[:adapter/jetty :example/web-1] {:port 8080, :handler #ig/ref :handler/greet}
 [:adapter/jetty :example/web-2] {:port 8081, :handler #ig/ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

This syntax sugar allows you to avoid adding extra `derive`
instructions to your source code.

### Composite references

Composite references complement composite keys. A normal reference
matches any key derived from the value of the reference. A composite
reference matches any key derived from every value in a vector.

For example:

```edn
{[:group/a :adapter/jetty] {:port 8080, :handler #ig/ref [:group/a :handler/greet]}
 [:group/a :handler/greet] {:name "Alice"}
 [:group/b :adapter/jetty] {:port 8081, :handler #ig/ref [:group/b :handler/greet]}
 [:group/b :handler/greet] {:name "Bob"}}
```

One use of composite references is to provide a way of grouping keys
in a configuration.

### Refs vs refsets

An Integrant ref is used to reference another key in the
configuration. The ref will be replaced with the initialized value of
the key. The ref does not need to refer to an exact key - the parent of
a derived key may be specified, so long as the ref is unambiguous.

For example suppose we have a configuration:

```edn
{:handler/greet    {:name #ig/ref :const/name}
 :const.name/alice {:name "Alice"}
 :const.name/bob   {:name "Bob"}}
```

And some definitions:

```clojure
(defmethod ig/init-key :const/name [_ {:keys [name]}]
  name)

(derive :const.name/alice :const/name)
(derive :const.name/bob   :const/name)
```

In this case `#ig/ref :const/name` is ambiguous - it could refer to
either `:const.name/alice` or `:const.name/bob`. To fix this we could
make the reference more specific:

```edn
{:handler/greet    {:name #ig/ref :const.name/alice}
 :const.name/alice {:name "Alice"}
 :const.name/bob   {:name "Bob"}}
```

But suppose we want to greet not just one person, but several. In this
case we can use a refset:

```edn
{:handler/greet-all {:names #ig/refset :const/name}
 :const.name/alice  {:name "Alice"}
 :const.name/bob    {:name "Bob"}}
```

When initialized, a refset will produce a set of all matching
values.

```clojure
(defmethod ig/init-key :handler/greet-all [_ {:keys [names]}]
  (fn [_] (resp/response (str "Hello " (clojure.string/join ", " names))))
```

### Asserting

It's often useful to add assertions to ensure that the system has been
initiated correctly. This can be done via `assert-key`:

```clojure
(defmethod ig/assert-key :adapter/jetty [_ {:keys [port]}]
  (assert (nat-int? port) ":port should be a valid port number"))
```

If we try to `init` an invalid configuration, then an `AssertionError`
is thrown explaining the error:

```
user=> (ig/init {:adapter/jetty {:port "3000"}})
AssertionError Assert failed: :port should be a valid port number
(nat-int? port)  user/eval3088/fn--3090 (form-init14815382800832764134.clj:2
```

This error is wrapped in an `clojure.lang.ExceptionInfo` that contains
additional information:

```
user=> (ex-data *e)
{:reason :integrant.core/build-failed-spec
 :system {}
 :key    :adapter/jetty
 :value  {:port "3000"}}
```

### Loading namespaces

It can be hard to remember to load all the namespaces that contain the
relevant multimethods. If you name your keys carefully, Integrant can
help via the `load-namespaces` function.

If a key has a namespace, `load-namespaces` will attempt to load
it. It will also try concatenating the name of the key onto the end of
its namespace, and loading that as well.

For example:

```clojure
(load-namespaces {:foo.component/bar {:message "hello"}})
```

This will attempt to load the namespace `foo.component` and also
`foo.component.bar`. A list of all successfully loaded namespaces will
be returned from the function. Missing namespaces are ignored.

### Annotations

Namespaced keywords may be annotated with metadata using the
`integrant.core/annotate` function:

```clojure
(ig/annotate :adapter/jetty
  {:doc "A Ring adapter for the Jetty webserver."})
```

This metadata can be recalled with `integrant.core/describe`:

```clojure
(ig/describe :adapter/jetty)
;; => {:doc "A Ring adapter for the Jetty webserver."}
```

### Loading hierarchies and annotations

Loading a Clojure namespace can be slow and potentially side-effectful,
and it's not possible to know ahead of time whether a namespace adds
keyword annotations or hierarchy information.

To solve this issue, Integrant provides ways of loading keyword
hierarchies and annotations from edn files on the classpath:

```clojure
(ig/load-hierarchy)
(ig/load-annotations)
```

These functions will search the classpath for files named
`integrant/hierarchy.edn` and `integrant/annotations.edn` respectively.
Including these files in a library will allow Integrant to quickly
discover information about the keywords used in the library.

## Reloaded workflow

See [Integrant-REPL](https://github.com/weavejester/integrant-repl) to
use Integrant systems at the REPL, in line with Stuart Sierra's [reloaded
workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded).

## Further Documentation

* [API docs](https://weavejester.github.io/integrant/integrant.core.html)

## License

Copyright © 2024 James Reeves

Released under the MIT license.
