#!/usr/bin/env bb

(ns script
  (:require [babashka.pods :as pods]
            [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest is testing]]))

(pods/load-pod "./pod-babashka-aws")

(require '[pod.babashka.aws :as aws])

(def s3 (aws/client {:api :s3}))

(deftest aws-ops
  (is (contains? (aws/ops s3) :ListBuckets)))

(deftest aws-doc
  ;; FIXME capture *out* in babashka, currently prints directly to System/out
  (is (= (with-out-str (aws/doc s3 :ListBuckets)) "TODO")))

(if (and (System/getenv "AWS_ACCESS_KEY_ID") (System/getenv "AWS_SECRET_ACCESS_KEY"))
  (deftest aws-invoke
    (is (= (keys (aws/invoke s3 {:op :ListBuckets})) [:Buckets :Owner])))
  (println "Skipping credential test"))


(deftest no-such-service
  (is (thrown-with-msg?
       Exception #"api :some-typo not available"
       (aws/client {:api :some-typo}))))

(def services (clojure.edn/read-string (slurp "resources/aws-services.edn")))

(testing "all clients of all available services"
  (doseq [service services]
    (is (= service (do (aws/client {:api service})
                       service)))))


(let [{:keys [:fail :error]} (t/run-tests)]
  (System/exit (+ fail error)))
