(ns credentials-test
  (:require
   [babashka.pods :as pods]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(if (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (do
    (println "Running native tests")
    (pods/load-pod "./pod-babashka-aws"))
  (do
    (println "Running JVM tests")
    (pods/load-pod ["clojure" "-M" "-m" "pod.babashka.aws"])))

(require '[pod.babashka.aws.credentials :as creds])

(defmacro with-system-properties [props & body]
  `(let [props# (System/getProperties)]
     (try
       (doseq [[k# v#] ~props]
         (System/setProperty k# v#))
       ~@body
       (finally
         (System/setProperties props#)))))

(defn create-temp-dir [prefix]
  (str (java.nio.file.Files/createTempDirectory
        prefix
        (into-array java.nio.file.attribute.FileAttribute []))))

(defn create-aws-credentials-file [content]
  (let [temp-dir (create-temp-dir "pod-babashka-aws")
        creds-file (clojure.java.io/file temp-dir ".aws/credentials")]
    (clojure.java.io/make-parents creds-file)
    (spit creds-file content)
    temp-dir))

(deftest aws-credentials-test
  (is (= (creds/fetch (creds/basic-credentials-provider {:access-key-id "key"
                                                          :secret-access-key "secret"}))
         #:aws{:access-key-id "key", :secret-access-key "secret"}))

  (with-system-properties {"aws.accessKeyId" "prop-key"
                           "aws.secretKey" "prop-secret"}
    (is (= (creds/fetch (creds/system-property-credentials-provider))
           #:aws{:access-key-id "prop-key", :secret-access-key "prop-secret"})))

  (with-system-properties {"aws.accessKeyId" "default-prop-key"
                           "aws.secretKey" "default-prop-secret"}
    (is (= (creds/fetch (creds/default-credentials-provider))
           #:aws{:access-key-id "default-prop-key", :secret-access-key "default-prop-secret"})))

  (is (= (creds/fetch (creds/basic-credentials-provider {:access-key-id "basic-key"
                                                          :secret-access-key "basic-secret"}))
         #:aws{:access-key-id  "basic-key", :secret-access-key "basic-secret"}))

  (let [home-dir (create-aws-credentials-file "[default]
aws_access_key_id=creds-prop-key
aws_secret_access_key=creds-prop-secret")]
    (with-system-properties {"user.home" home-dir}
      (is (= (creds/fetch (creds/profile-credentials-provider))
             #:aws{:access-key-id "creds-prop-key", :secret-access-key "creds-prop-secret"
                   :session-token nil}))))

  (let [home-dir (create-aws-credentials-file "[custom]
aws_access_key_id=creds-custom-prop-key
aws_secret_access_key=creds-custom-prop-secret")]
    (with-system-properties {"user.home" home-dir
                             "aws.profile" "custom"}
      (is (= (creds/fetch (creds/profile-credentials-provider))
             #:aws{:access-key-id "creds-custom-prop-key", :secret-access-key "creds-custom-prop-secret"
                   :session-token nil}))))


  (let [expiration (str (.plus (java.time.Instant/now) 10 java.time.temporal.ChronoUnit/MINUTES))
        creds-file-content (format "[custom]
credential_process = echo '{\"AccessKeyId\":\"creds+-custom-prop-key\",\"SecretAccessKey\":\"creds+-custom-prop-secret\",\"Version\":1,\"Expiration\":\"%s\"}'" expiration)
        home-dir (create-aws-credentials-file creds-file-content)]
    (with-system-properties {"user.home" home-dir
                             "aws.profile" "custom"}
      (is (= (creds/fetch (creds/credential-process-credentials-provider))
             #:aws{:access-key-id "creds+-custom-prop-key",
                   :secret-access-key "creds+-custom-prop-secret"
                   :session-token nil
                   :cognitect.aws.credentials/ttl (dec 300)}))))


  (let [expiration (str (.plus (java.time.Instant/now) 10 java.time.temporal.ChronoUnit/MINUTES))
        session-token "my-session-token"
        creds-file-content (format "[custom]
credential_process = echo '{\"AccessKeyId\":\"creds+-custom-prop-key\",\"SecretAccessKey\":\"creds+-custom-prop-secret\",\"SessionToken\":\"%s\",\"Version\":1,\"Expiration\":\"%s\"}'"
                                   session-token
                                   expiration)
        home-dir (create-aws-credentials-file creds-file-content)]
    (with-system-properties {"user.home" home-dir
                             "aws.profile" "custom"}
      (is (= (creds/fetch (creds/credential-process-credentials-provider))
             #:aws{:access-key-id "creds+-custom-prop-key",
                   :secret-access-key "creds+-custom-prop-secret"
                   :session-token session-token
                   ;; Test runs within a second, so with flooring and the cutoff of 300, the ttl is 299
                   :cognitect.aws.credentials/ttl 299})))))

(require '[pod.babashka.aws.config :as config])

(deftest aws-config-test
  (let [creds-file-content "[custom]
aws_access_key_id=creds-custom-prop-key
aws_secret_access_key=creds-custom-prop-secret"
        home-dir (create-aws-credentials-file creds-file-content)]
    (is (= (config/parse (str home-dir

                              "/.aws/credentials"))
           {"custom" {"aws_access_key_id" "creds-custom-prop-key",
                      "aws_secret_access_key" "creds-custom-prop-secret"}))))

(when-not (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (shutdown-agents))

(let [{:keys [:fail :error]} (t/run-tests)]
  (System/exit (+ fail error)))
