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
