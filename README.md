# Integrant

> integrant /ˈɪntɪɡr(ə)nt/
>
> (of parts) making up or contributing to a whole; constituent.

Integrant is a Clojure micro-framework for building applications with
data-driven architecture. It can be thought of as an alternative
to [Component][] or [Mount][], and was inspired by [Arachne][] and by
through work on [Duct][].

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

In Component, only records or maps may be components. Anything else
you might want to have included in the dependency graph, like a
function, needs to be wrapped in a record.

In Integrant, anything can be dependent on anything else. The
dependencies are resolved from the configuration before it's
initialized into a system.

[edn]: https://github.com/edn-format/edn

## Installation

To install, add the following to your project `:dependencies`:

    [integrant "0.1.0"]

## Usage

Integrant starts with a configuration map. Each top-level key in the
map represents a configuration that can be "initialized" into a
concrete implementation. Configurations can reference other keys via
the `ref` function.

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
initialize a key:

```clojure
(require '[ring.jetty.adapter :as jetty]
         '[ring.util.response :as resp])

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler] :as opts}]
  (jetty/run-jetty handler (-> opts (dissoc :handler) (assoc :join? false))))

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

Like Component, `halt!` shuts down the system in reverse dependency
order. Unlike Component, `halt!` is entirely side-effectful. The
return value should be ignored, and the system structure discarded.

## License

Copyright © 2016 James Reeves

Released under the MIT license.
