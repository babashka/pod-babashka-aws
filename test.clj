#!/usr/bin/env bb

(require '[babashka.pods :as pods])

(if (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (pods/load-pod "./pod-babashka-aws")
  (pods/load-pod ["clojure" "-M" "-m" "pod.babashka.ssh"]))

(require '[pod.babashka.aws :as aws])

(def s3 (aws/client {:api :s3}))

(prn (keys (aws/ops s3)))

(when-not (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (shutdown-agents)
  (System/exit 0))
