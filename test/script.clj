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

;; TODO maybe less implicit config for this
(defn localstack? []
  (= "test" (System/getenv "AWS_ACCESS_KEY_ID") (System/getenv "AWS_SECRET_ACCESS_KEY")))

(def localstack-endpoint {:protocol :http :hostname "localhost" :port 4566})

(def region (System/getenv "AWS_REGION"))
(def s3 (aws/client (merge
                     {:api :s3 :region (or region "eu-central-1") }
                     (when (localstack?)
                       {:endpoint-override localstack-endpoint}))))

(def lambda (aws/client (merge
                         {:api :lambda :region (or region "eu-central-1") }
                         (when (localstack?)
                           {:endpoint-override localstack-endpoint}))))

(deftest aws-ops-test
  (is (contains? (aws/ops s3) :ListBuckets)))

(deftest aws-doc-test
  (is (str/includes?
       (with-out-str (aws/doc s3 :ListBuckets))
       "Returns a list of all buckets")))

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
              _ (is (= (count png) (count bytes)))
              put3 (testing "file arg"
                     (aws/invoke s3 {:op :PutObject
                                     :request {:Bucket "pod-babashka-aws"
                                               :Key "logo.png"
                                               :Body (io/file "resources" "babashka.png")}}))
              _ (is (not (:Error put3)))
              get3 (aws/invoke s3 {:op :GetObject
                                   :request {:Bucket "pod-babashka-aws"
                                             :Key "logo.png"}})
              bytes (read-bytes (:Body get3))
              _ (is (= (count png) (count bytes)))

              lambda-resp (aws/invoke lambda {:op :ListFunctions})
              _ (is (= {:Functions []} lambda-resp))]
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

(require '[pod.babashka.aws.logging :as logging])

(deftest logging-test
  (logging/set-level! :info)
  (let [s3 (aws/client (merge
                        {:api :s3 :region (or region "eu-central-1") }
                        (when (localstack?)
                          {:endpoint-override localstack-endpoint})))]
    (aws/invoke s3 {:op :ListBuckets})))

(when-not (= "executable" (System/getProperty "org.graalvm.nativeimage.kind"))
  (shutdown-agents))

(let [{:keys [:fail :error]} (t/run-tests)]
  (System/exit (+ fail error)))
