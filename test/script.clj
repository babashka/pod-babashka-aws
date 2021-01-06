#!/usr/bin/env bb

(ns script
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

(require '[pod.babashka.aws :as aws])

(def region (System/getenv "AWS_REGION"))
(def s3 (aws/client {:api :s3 :region
                     (or region "eu-central-1")}))

(deftest aws-ops-test
  (is (contains? (aws/ops s3) :ListBuckets)))

(deftest aws-doc-test
  (is (str/includes?
       (with-out-str (aws/doc s3 :ListBuckets))
       "Returns a list of all buckets")))

(require '[pod.babashka.aws.credentials :as creds])

(defmacro with-system-properties [props & body]
  `(let [props# (System/getProperties)]
     (try
       (doseq [[k# v#] ~props]
         (System/setProperty k# v#))
       ~@body
       (finally
         (System/setProperties props#)))))

(defn create-temp-dir [prefix affix]
  (let [f (java.io.File/createTempFile prefix affix)]
    (.delete f)
    (.mkdir f)
    f))


(defn create-aws-credentials-file [content]
  (let [temp-dir (create-temp-dir "pod-babashka-aws-" "-credentials")
        creds-file (clojure.java.io/file temp-dir ".aws/credentials")]
    (clojure.java.io/make-parents creds-file)
    (spit creds-file content)
    ;; Return home dir
    (.getPath temp-dir)))

(deftest aws-credentials-test
  (is (= (creds/-fetch (creds/basic-credentials-provider {:access-key-id "key"
                                                          :secret-access-key "secret"}))
         #:aws{:access-key-id "key", :secret-access-key "secret"}))

  (with-system-properties {"aws.accessKeyId" "prop-key"
                           "aws.secretKey" "prop-secret"}
    (is (= (creds/-fetch (creds/system-property-credentials-provider))
           #:aws{:access-key-id "prop-key", :secret-access-key "prop-secret"})))

  (with-system-properties {"aws.accessKeyId" "default-prop-key"
                           "aws.secretKey" "default-prop-secret"}
    (is (= (creds/-fetch (creds/default-credentials-provider))
           #:aws{:access-key-id "default-prop-key", :secret-access-key "default-prop-secret"})))

  (is (= (creds/-fetch (creds/basic-credentials-provider {:access-key-id "basic-key"
                                                          :secret-access-key "basic-secret"}))
         #:aws{:access-key-id  "basic-key", :secret-access-key "basic-secret"}))

  (let [home-dir (create-aws-credentials-file "[default]
aws_access_key_id=creds-prop-key
aws_secret_access_key=creds-prop-secret")]
    (with-system-properties {"user.home" home-dir}
      (is (= (creds/-fetch (creds/profile-credentials-provider))
             #:aws{:access-key-id "creds-prop-key", :secret-access-key "creds-prop-secret"
                   :session-token nil}))))

  (let [home-dir (create-aws-credentials-file "[custom]
aws_access_key_id=creds-custom-prop-key
aws_secret_access_key=creds-custom-prop-secret")]
    (with-system-properties {"user.home" home-dir
                             "aws.profile" "custom"}
      (is (= (creds/-fetch (creds/profile-credentials-provider))
             #:aws{:access-key-id "creds-custom-prop-key", :secret-access-key "creds-custom-prop-secret"
                   :session-token nil}))))


  (let [expiration (str (.plus (java.time.Instant/now) 10 java.time.temporal.ChronoUnit/MINUTES))
        creds-file-content (format "[custom]
credential_process = echo '{\"AccessKeyId\":\"creds+-custom-prop-key\",\"SecretAccessKey\":\"creds+-custom-prop-secret\",\"Version\":1,\"Expiration\":\"%s\"}'" expiration)
        home-dir (create-aws-credentials-file creds-file-content)]
    (with-system-properties {"user.home" home-dir
                             "aws.profile" "custom"}
      (is (= (creds/-fetch (creds/profile-credentials-provider+))
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
      (is (= (creds/-fetch (creds/profile-credentials-provider+))
             #:aws{:access-key-id "creds+-custom-prop-key",
                   :secret-access-key "creds+-custom-prop-secret"
                   :session-token session-token
                   ;; Test runs within a second, so with flooring and the cutoff of 300, the ttl is 299
                   :cognitect.aws.credentials/ttl 299})))))


(deftest aws-invoke-test
  ;; tests cannot be conditionally defined in bb currently, see #705, so moved
  ;; the conditions inside the test
  (if (and (System/getenv "AWS_ACCESS_KEY_ID")
           (System/getenv "AWS_SECRET_ACCESS_KEY")
           region)
    (do (is (= (keys (aws/invoke s3 {:op :ListBuckets})) [:Buckets :Owner]))
        (let [png (java.nio.file.Files/readAllBytes
                   (.toPath (io/file "resources" "babashka.png")))
              ;; ensure bucket, ignore error if it already exists
              _bucket-resp (aws/invoke
                            s3
                            {:op :CreateBucket
                             :request {:Bucket "pod-babashka-aws"
                                       :CreateBucketConfiguration {:LocationConstraint region}}})
              put1 (aws/invoke s3 {:op :PutObject
                                   :request {:Bucket "pod-babashka-aws"
                                             :Key "logo.png"
                                             :Body png}})
              _ (is (not (:Error put1)))
              get1 (aws/invoke s3 {:op :GetObject
                                   :request {:Bucket "pod-babashka-aws"
                                             :Key "logo.png"}})
              read-bytes (fn [is]
                           (let [baos (java.io.ByteArrayOutputStream.)]
                             (io/copy is baos)
                             (.toByteArray baos)))
              bytes (read-bytes (:Body get1))
              _ (is (= (count png) (count bytes)))
              put2 (testing "inputstream arg"
                     (aws/invoke s3 {:op :PutObject
                                     :request {:Bucket "pod-babashka-aws"
                                               :Key "logo.png"
                                               :Body (io/input-stream
                                                      (io/file "resources" "babashka.png"))}}))
              _ (is (not (:Error put2)))
              get2 (aws/invoke s3 {:op :GetObject
                                   :request {:Bucket "pod-babashka-aws"
                                             :Key "logo.png"}})
              bytes (read-bytes (:Body get2))
              _ (is (= (count png) (count bytes)))]
          :the-end))
    (println "Skipping credential test")))

(deftest no-such-service-test
  (is (thrown-with-msg?
       Exception #"api :some-typo not available"
       (aws/client {:api :some-typo}))))

(def services (edn/read-string (slurp "resources/aws-services.edn")))

(deftest all-services-test
  (testing "all clients of all available services"
    (doseq [service services]
      (is (= service (do (aws/client {:api service})
                         service))))))

(when-not (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (shutdown-agents))

(let [{:keys [:fail :error]} (t/run-tests)]
  (System/exit (+ fail error)))
