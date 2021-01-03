# pod-babashka-aws

## API

Available namespaces:

- `pod.babashka.aws...`

## Example

``` clojure
(require '[babashka.pods :as pods])

(pods/load-pod "./pod-babashka-aws")

...
```

## Build

Run `script/compile`. This requires `GRAALVM_HOME` to be set.

## Test

To test the pod code with JVM clojure, run `clojure -M test/script.clj`.

To test the native image with bb, run `bb test/script.clj`.

## License

Copyright © 2020 Michiel Borkent

Distributed under the Apache License 2.0. See LICENSE.
