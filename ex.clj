(ns com.grzm.ex.pod-aws
  (:require
   [babashka.pods :as pods]))

(when-let [jul-config (System/getProperty "java.util.logging.config.file")]
  (println "java.util.logging.config.file:" jul-config))

(pods/load-pod "./pod-babashka-aws")
(require '[pod.babashka.aws :as aws])

(defn ex [_]
  (let [s3 (aws/client {:api :s3})]
    (prn (keys (aws/invoke s3 {:op :ListBuckets}))))
  (let [sts (aws/client {:api :sts})]
    (prn (keys (aws/invoke sts {:op :GetCallerIdentity})))))

(ex nil)

