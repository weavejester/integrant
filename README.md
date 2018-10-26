# Integrant

[![Build Status](https://travis-ci.org/weavejester/integrant.svg?branch=master)](https://travis-ci.org/weavejester/integrant)

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

To use the latest release, add the following to your project
`:dependencies`:

    [integrant "0.7.0"]

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
initialized first, and it's value replaced with a handler function.
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

The `resume` function acts like `init`, but takes an additional
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
accept requests but wait around until its resumed.

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

### Prepping

Sometimes keys require some preparation. Perhaps you have a
particularly complex set of default values, or perhaps you want to add
in default references to other keys. In these cases, the `prep-key`
method can help.

```clojure
(defmethod ig/prep-key :adapter/jetty [_ config]
  (merge {:port 8080} config))
```

The `prep-key` method will change the value of a key *before* the
configuration is initialized. In the previous example, the `:port`
would default to `8080` if not set.

All keys in a configuration can be prepped using the `prep` function
before the `init` function:

```clojure
(-> config ig/prep ig/init)
```

If `prep-key` is not defined, it defaults to the identity
function. Prepping keys is particularly useful when adding default
references to derived keywords.

### Derived keywords

Keywords have an inherited hierarchy. Integrant takes advantage of
this by allowing keywords to refer to their descendents. For example:

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

So your could also write:

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

### Specs

It would be incorrect to write specs directly against the keys used by
Integrant, as the same key will used in the configuration, during
initiation, and in the resulting system. All will likely have
different values.

To resolve this, Integrant has an `pre-init-spec` multimethod that can 
be extended to provide Integrant with a spec to test the value after
the references are resolved, but before they are initiated. The
resulting spec is checked directly before `init-key`, and an exception
is raised if it fails.

Here's how our two example keys would be specced out:

```clojure
(require '[clojure.spec.alpha :as s])

(s/def ::port pos-int?)
(s/def ::handler fn?)

(defmethod ig/pre-init-spec :adapter/jetty [_]
  (s/keys :req-un [::port ::handler]))
  
(s/def ::name string?)

(defmethod ig/pre-init-spec :handler/greet [_]
  (s/keys :req-un [::name]))
```

If we try to `init` an invalid configuration:

```clojure
(ig/init {:adapter/jetty {:port 3000} :handler/greet {:name "foo"}})
```

Then an `ExceptionInfo` is thrown explaining the error:

```
ExceptionInfo Spec failed on key :adapter/jetty when building system
val: {:port 3000} fails predicate: (contains? % :handler)
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

## Reloaded workflow

See [Integrant-REPL](https://github.com/weavejester/integrant-repl) to 
use Integrant systems at the REPL, in line with Stuart Sierra's [reloaded 
workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded).

## Further Documentation

* [API docs](https://weavejester.github.io/integrant/integrant.core.html)

## License

Copyright © 2018 James Reeves

Released under the MIT license.
