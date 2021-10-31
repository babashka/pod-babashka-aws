(require '[babashka.pods :as pods])

(pods/load-pod ["clojure" "-M" "-m" "pod.babashka.aws"])

(require '[pod.babashka.aws :as aws])

(aws/set-logging-config! "java.util.logging.ConsoleHandler.level = TRACE")

(defn ex [_]
  (let [s3 (aws/client {:api :s3})]
    (prn (aws/invoke s3 {:op :ListBuckets})))
  (let [sts (aws/client {:api :sts})]
    (prn (aws/invoke sts {:op :GetCallerIdentity}))))

(ex nil)
