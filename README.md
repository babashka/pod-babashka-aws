# pod-babashka-aws

## API

The namespaces and functions in this pod reflect those in the official
[aws-api](https://github.com/cognitect-labs/aws-api) library.

Available namespaces and functions:

- `pod.babashka.aws`: `client`, `doc`, `invoke`

## Example

``` clojure
#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(pods/load-pod 'org.babashka/aws "0.0.1")

(require '[pod.babashka.aws :as aws])

(def s3-client
  (aws/client {:api :s3 :region "eu-central-1"}))

(aws/doc s3-client :ListBuckets)

(aws/invoke s3-client {:op :ListBuckets})
```

See [test/script.clj](test/script.clj) for an example script.

## Build

Run `script/compile`. This requires `GRAALVM_HOME` to be set.

## Test

To test the pod code with JVM clojure, run `clojure -M test/script.clj`.

To test the native image with bb, run `bb test/script.clj`.

## License

Copyright © 2020 Michiel Borkent, Jeroen van Dijk and Rahul De.

Distributed under the Apache License 2.0. See LICENSE.
