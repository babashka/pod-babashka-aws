# pod-babashka-aws

A [babashka](https://github.com/babashka/babashka)
[pod](https://github.com/babashka/pods) wrapping the
[aws-cli](https://github.com/cognitect-labs/aws-api) library.

## Status

Experimental, awaiting your feedback.

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

(def region "eu-central-1")

(def s3-client
  (aws/client {:api :s3 :region region}))

(aws/doc s3-client :ListBuckets)

(aws/invoke s3-client {:op :ListBuckets})

(aws/invoke s3-client
            {:op :CreateBucket
             :request {:Bucket "pod-babashka-aws"
                       :CreateBucketConfiguration {:LocationConstraint region}}})

(require '[clojure.java.io :as io])

(aws/invoke s3-client
            {:op :PutObject
             :request {:Bucket "pod-babashka-aws"
                       :Key "logo.png"
                       :Body (io/input-stream
                              (io/file "resources" "babashka.png"))}})
```

See [test/script.clj](test/script.clj) for an example script.

## Credentials

The default behaviour for credentials is the same way as Cognitect's [`aws-api`](https://github.com/cognitect-labs/aws-api#credentials); meaning the client implicitly looks up credentials the same way the [java SDK does](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) .

To provide credentials explicitly, you can pass a `credentials-provider` to the client constructor fn, e.g.

```clojure
(require '[pod.babashka.aws :as aws])
(require '[pod.babashka.aws.credentials :as credentials])

(def s3 (aws/client {:api :s3 :credentials-provider (credentials/basic-credentials-provider
                                                      {:access-key-id     "ABC"
                                                       :secret-access-key "XYZ"})}))
```
In contrast to the `aws-api` library, due to Pod limitations, this pod does not support extending the CredentialsProvider interface. For more complex flows, e.g. temporary credentials obtained by `AWS SSO`, one can use a `credential_process` entry in a `~/.aws/credentials` file:

```clojure
(def s3 (aws/client {:api :s3 :credentials-provider (credentials/credential-process-credentials-provider
                                                      "custom-profile")}))
```

`~/.aws/credentials` could look like
```
[custom-profile]
   credential_process = bb fetch_sso_credentials.clj
```

The `credential_process` entry can be any cli command that prints the right json data ([more info here](https://docs.aws.amazon.com/credref/latest/refdocs/setting-global-credential_process.html)):
```clojure
#!/usr/bin/env bb

(println "{\"AccessKeyId\":\"***\",\"SecretAccessKey\":\"***\",\"SessionToken\":\"***\",\"Expiration\":\"2020-01-00T00:00:00Z\",\"Version\":1}")
```

## Differences with aws-cli

- Credentials: custom flows are supported, but not by extending CredentialsProvider interface, see <a href="#credentials">Credentials</a>) for options.
- This pod doesn't require adding dependencies for each AWS service.
- Async might be added in a later version.

## Build

Run `script/compile`. This requires `GRAALVM_HOME` to be set.

## Test

To test the pod code with JVM clojure, run `clojure -M test/script.clj`.

To test the native image with bb, run `bb test/script.clj`.

To test with [localstack](https://github.com/localstack/localstack)

``` shell
# Start localstack
docker-compose up -d

# Set test credentials and run tests
AWS_REGION=eu-north-1 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test script/test
```


## License

Copyright © 2020 Michiel Borkent, Jeroen van Dijk, Rahul De and Valtteri Harmainen.

Distributed under the Apache License 2.0. See LICENSE.
