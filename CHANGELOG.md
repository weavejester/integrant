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
