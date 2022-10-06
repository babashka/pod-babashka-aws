> # pod-babashka-aws <sup>* *deprecated*</sup>
>
> **Great news! :tada:** [Awyeah-api](https://github.com/grzm/awyeah-api) is a babashka compatible re-implementation of Cognitect's AWS API!
This means that this pod is no longer necessary, and will no longer be maintained.
Please use [Awyeah-api](https://github.com/grzm/awyeah-api).

A [babashka](https://github.com/babashka/babashka)
[pod](https://github.com/babashka/pods) wrapping the
[aws-api](https://github.com/cognitect-labs/aws-api) library.

## Status

Experimental, awaiting your feedback.

## API

The namespaces and functions in this pod reflect those in the official
[aws-api](https://github.com/cognitect-labs/aws-api) library.

Available namespaces and functions:

- `pod.babashka.aws`: `client`, `doc`, `invoke`
- `pod.babashka.aws.config`: `parse`
- `pod.babashka.aws.credentials`: `credential-process-credentials-provider`,
  `basic-credentials-provider`, `default-credentials-provider`,
  `fetch`, `profile-credentials-provider`, `system-property-credentials-provider`
- `pod.babashka.aws.logging`: `set-level!`

## Examples

### Single-file script

``` clojure
#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(pods/load-pod 'org.babashka/aws "0.1.2")

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
                       :Body (io/file "resources" "babashka.png")}})
```

See [test/script.clj](test/script.clj) for an example script.

### Project with a bb.edn file

`bb.edn`:

```clojure
{:pods {org.babashka/aws {:version "0.1.2"}}} ; this will be loaded on demand
```

Your code:

```clojure
(ns my.code
  (:require [pod.babashka.aws :as aws] ; required just like a normal Clojure library
            [clojure.java.io :as io]))
  
(def region "eu-central-1")

(def s3-client
  (aws/client {:api :s3 :region region}))

(aws/doc s3-client :ListBuckets)

(aws/invoke s3-client {:op :ListBuckets})

(aws/invoke s3-client
            {:op :CreateBucket
             :request {:Bucket "pod-babashka-aws"
                       :CreateBucketConfiguration {:LocationConstraint region}}})

(aws/invoke s3-client
            {:op :PutObject
             :request {:Bucket "pod-babashka-aws"
                       :Key "logo.png"
                       :Body (io/file "resources" "babashka.png")}})
```

## Differences with aws-api

- Credentials: custom flows are supported, but not by extending CredentialsProvider interface. See <a href="#credentials">Credentials</a> for options.
- This pod doesn't require adding dependencies for each AWS service.
- Async might be added in a later version.
- For uploading (big) files (e.g. to S3), it is better for memory consumption to
  pass a `java.io.File` directly, rather than an input-stream.

## Credentials

The default behaviour for credentials is the same way as Cognitect's
[`aws-api`](https://github.com/cognitect-labs/aws-api#credentials); meaning the
client implicitly looks up credentials the same way the [java SDK
does](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html)
.

To provide credentials explicitly, you can pass a `credentials-provider` to the
client constructor fn, e.g.:

```clojure
(require '[pod.babashka.aws :as aws])
(require '[pod.babashka.aws.credentials :as credentials])

(def s3 (aws/client {:api :s3
                     :credentials-provider (credentials/basic-credentials-provider
                                            {:access-key-id     "ABC"
                                             :secret-access-key "XYZ"})}))
```

In contrast to the `aws-api` library this pod does not support extending the
CredentialsProvider interface. For more complex flows, e.g. temporary
credentials obtained by `AWS SSO`, one can use a `credential_process` entry in a
`~/.aws/credentials` file, as documented [here](https://docs.aws.amazon.com/credref/latest/refdocs/setting-global-credential_process.html):

```clojure
(def s3 (aws/client {:api :s3
                     :credentials-provider (credentials/credential-process-credentials-provider
                                            "custom-profile")}))
```

where `~/.aws/credentials` could look like

```
[custom-profile]
   credential_process = bb fetch_sso_credentials.clj
```

The `credential_process` entry can be any program that prints the expected JSON data:

```clojure
#!/usr/bin/env bb

(println "{\"AccessKeyId\":\"***\",\"SecretAccessKey\":\"***\",\"SessionToken\":\"***\",\"Expiration\":\"2020-01-00T00:00:00Z\",\"Version\":1}")
```

## Logging

The aws-api includes clojure.tools.logging using the default java.util.logging
implementation. Use `pod.babashka.aws.logging/set-level!` to change the level of
logging in the pod. The default log level in the pod is `:warn`. All possible
levels: `:trace`, `:debug`, `:info`, `:warn`, `:error` or `:fatal`.

Each aws-api client has its own logger which adopts the logging level
of the global logger at time it was instantiated.

```clojure
user=> (def sts-1 (aws/client {:api :sts}))
2021-11-10 23:07:13.608:INFO::main: Logging initialized @30234ms to org.eclipse.jetty.util.log.StdErrLog
#'user/sts-1

user=> (keys (aws/invoke sts-1 {:op :GetCallerIdentity}))
(:UserId :Account :Arn)

user=> (require '[pod.babashka.aws.logging :as logging])
nil

user=> (logging/set-level! :info)
nil
user=> (def sts-2 (aws/client {:api :sts}))
#'user/sts-2

user=> (keys (aws/invoke sts-2 {:op :GetCallerIdentity}))
Nov 10, 2021 11:07:45 PM clojure.tools.logging$eval9973$fn__9976 invoke
INFO: Unable to fetch credentials from environment variables.
Nov 10, 2021 11:07:45 PM clojure.tools.logging$eval9973$fn__9976 invoke
INFO: Unable to fetch credentials from system properties.
(:UserId :Account :Arn)

;; The sts-1 client still has level :warn:
user=> (keys (aws/invoke sts-1 {:op :GetCallerIdentity}))
(:UserId :Account :Arn)
```

## Build

Run `script/compile`. This requires `GRAALVM_HOME` to be set.

## Update aws-api deps

Run `script/update-deps.clj`.

## Test

Run `script/test`. This will run both the pod and tests (defined in
`test/script.clj`) in two separate JVMs.

To test the native-image together with babashka, run the tests while setting
`APP_TEST_ENV` to `native`:

``` shell
APP_TEST_ENV=native script/test
```

To test with [localstack](https://github.com/localstack/localstack):

``` shell
# Start localstack
docker-compose up -d

# Set test credentials and run tests
AWS_REGION=eu-north-1 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test script/test
```

When adding new services to tests you need to add the service name into `SERVICES` environment variable in `docker-compose.yml` and `.circleci/config.yml`. See [localstack docs](https://github.com/localstack/localstack#configurations) for a list of available services.

## License

Copyright © 2020 Michiel Borkent, Jeroen van Dijk, Rahul De and Valtteri Harmainen.

Distributed under the Apache License 2.0. See LICENSE.
