# Integrant

Integrant is a Clojure micro-framework for building data-driven
systems. It can be thought of as an alternative to [Component][]
or [Mount][].

[component]: https://github.com/stuartsierra/component
[mount]: https://github.com/tolitius/mount

## Rationale

Integrant was built as a reaction to two potential weaknesses in
Component's design.

The first weakness is that while Component makes use of immutable
records, it has no direct way to convert a configuration map into a
running system. Components are typically created through constructor
functions, and systems created programmatically. Ideally we'd prefer
to define the architecture of our system through a data structure.

The second weakness is that dependencies in a system exist only
between components, and those dependencies are unordered. If you want
function to depend on another component, you either have to wrap the
function in a record, or hard code the dependency outside of the
system. If you have a list of dependant rules or routes, you need to
a bespoke way of ordering them.

Integrant solves both these weaknesses and more, with minimal
tradeoffs.

## Installation

To install, add the following to your project `:dependencies`:

    [integrant "0.1.0-SNAPSHOT"]

## Usage

Integrant starts with a configuration map. Each top-level key in the
map represents a configuration that can be turned into a concrete
implementation. Configurations can reference other keys via the `ref`
function.

For example:

```clojure
(require '[integrant.core :as ig])

(def config
  {:adapter/jetty {:port 8080, :handler (ig/ref :handler/greet)}
   :handler/greet {:name "Alice"}})
```

Alternatively, you can specify your configuration as pure edn:

```edn
{:adapter/jetty {:port 8080, :handler #ref :handler/greet}
 :handler/greet {:name "Alice"}}
```

And load it with `read-string`:

```clojure
(def config
  (ig/read-string (slurp "config.edn")))
```

Once you have a configuration, Integrant needs to be told how to
implement it. The `init-key` multimethod tells Integrant how to
initiate a key:

```clojure
(require '[ring.jetty.adapter :as jetty]
         '[ring.util.response :as resp])

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (jetty/run-jetty handler (-> opts (dissoc :handler) (assoc :join? false)))

(defmethod ig/init-key :handler/greet [_ {:keys [name]}]
  (fn [_] (resp/response (str "Hello " name))))
```

While the `halt-key!` multimethod tells Integrant how to stop and
clean up after a key:

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

Unlike Component, `halt!` is entirely side-effectful. The return value
should be ignored, and the system discarded.

## License

Copyright © 2016 James Reeves

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
