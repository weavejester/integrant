## 0.9.0-alpha3 (2023-11-30)

* Fixed bug expanding keys with empty map values

## 0.9.0-alpha2 (2023-09-08)

* Changed expand overrides to need `^:override` metadata
* Added `load-hierarchy` function

## 0.9.0-alpha1 (2023-08-04)

* **BREAKING CHANGE:** removed `pre-init-spec`
* **BREAKING CHANGE:** removed old `expand` function
* Added `assert-key` to replace `pre-init-spec`
* Added `expand-key` and new `expand` function (#102)

## 0.8.1 (2023-05-26)

* Added `ref-resolve` method to `RefLife` protocol (#68)
* Added ClojureScript support to `read-string` (#75)
* Added `normalize-key` function to the public API (#76)
* Improved error message for `ref` and `refset` (#70)

## 0.8.0 (2020-01-04)

* Added check to ensure keys have namespaces

## 0.8.0-alpha2 (2018-10-26)

* Fixed `resolve-ref` not working under `resume`

## 0.8.0-alpha1 (2018-10-21)

* Added `resolve-ref` multimethod

## 0.7.0 (2018-09-28)

* Added check for invalid composite keys (#39)

## 0.7.0-alpha2 (2018-04-09)

* Stopped refsets from automatically including dependent keys
* Fixed halting of dependency keys in `resume`

## 0.7.0-alpha1 (2018-01-24)

* Added `refset` function and tags
* Added `prep` function and associated `prep-key` method
* Added `key-comparator` function
* Added `fold` function for reducing system
* Fixed dependency ordering to be fully deterministic

## 0.6.3 (2017-12-24)

* Fixed `load-namespaces` to load ancestor namespaces (#35)
* Fixed `halt!`, `run!` and `reverse-run!` to ignore keys not in system (#36)

## 0.6.2 (2017-12-09)

* Fixed dispatch of `pre-init-spec` not working with composite keys
* Updated Clojure dependency to 1.9.0 final release

## 0.6.1 (2017-08-19)

* Added keys argument to `load-namespaces`

## 0.6.0 (2017-08-08)

* Added `pre-init-spec` multimethod for adding specs to keys
* Updated minimum Clojure version to 1.9

## 0.5.0 (2017-07-28)

* Added composite references

## 0.4.1 (2017-07-17)

* Fixed halting of missing keys when resuming a suspended system

## 0.4.0 (2017-04-21)

* **BREAKING CHANGE:** Changed `#ref` data reader to `#ig/ref` (#12)

## 0.3.3 (2017-03-29)

* Fixed error caused by sorting keys in exceptions (#17)

## 0.3.2 (2017-03-28)

* Fixed ordering problem in `build` introduced in 0.3.1 (#16)

## 0.3.1 (2017-03-28)

* Fixed `load-namespaces` for composite keys (#14)
* Wrapped exceptions for `build`, `run!` and `reverse-run` in `ExceptionInfo` (#11)

## 0.3.0 (2017-03-17)

* Added composite keys

## 0.2.3 (2017-03-09)

* Changed `build` to ignore errors for irrelevant keys
* Changed `build` to exclude irrelevant keys from the system

## 0.2.2 (2017-02-18)

* Fixed `dependency-graph` to resolve derived refs (#10)

## 0.2.1 (2017-02-06)

* Added `find-derived-1` function

## 0.2.0 (2017-01-19)

* **BREAKING CHANGE:** Removed default behavior for `init-key` (#6)
* Fixed functions that restrict by key to include referenced dependencies
* Added support for using derived keys
* Added `find-derived` function

## 0.1.5 (2016-12-23)

* Ensured `resume` halts missing keys

## 0.1.4 (2016-12-13)

* Added `load-namespaces` function

## 0.1.3 (2016-12-12)

* Added`suspend!` and `resume` functions
* Added `run!` and `reverse-run!` functions (#4)

## 0.1.2 (2016-12-10)

* Added ClojureScript support (#5)
* Added `build` function for custom initialization (#3)

## 0.1.1 (2016-12-07)

* Fixed bug to avoid walking previously expanded keys (#2)
* Removed `expand-1` function

## 0.1.0 (2016-12-06)

* Initial release
