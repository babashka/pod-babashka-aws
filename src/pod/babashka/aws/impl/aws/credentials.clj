(ns pod.babashka.aws.impl.aws.credentials
  (:require
   [clojure.data.json :as json]
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cognitect.aws.config :as config]
   [cognitect.aws.credentials :as creds]
   [cognitect.aws.util :as u]
   [pod.babashka.aws.impl.aws]))

(set! *warn-on-reflection* true)

;;; Pod Backend

(def *providers (atom {}))

(defn create-provider [provider]
  (let [provider-id (java.util.UUID/randomUUID)]
    (swap! *providers assoc provider-id provider)
    {:provider-id provider-id}))

(defmacro with-system-properties [props & body]
  `(let [props# (System/getProperties)]
     (try
       (doseq [[k# v#] ~props]
         (System/setProperty k# v#))
       ~@body
       (finally
         (System/setProperties props#)))))

(defn get-provider [config]
  (get @*providers (get config :provider-id)))

(defn -basic-credentials-provider [conf]
  (create-provider (creds/basic-credentials-provider conf)))

(defn -environment-credentials-provider []
  (create-provider (creds/environment-credentials-provider)))

(defn -system-property-credentials-provider [jvm-props]
  (with-system-properties jvm-props
    (create-provider (creds/system-property-credentials-provider))))

(defn -profile-credentials-provider
  ([jvm-props]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider))))

  ([jvm-props profile-name]
   (with-system-properties jvm-props
     (create-provider (creds/profile-credentials-provider profile-name))))

  ([_jvm-props _profile-name ^java.io.File _f]
   (throw (ex-info "profile-credentials-provider with 2 arguments not supported yet" {}))))

(def windows? (-> (System/getProperty "os.name")
                  (str/lower-case)
                  (str/includes? "win")))

;; parse-cmd accept the following formats:
#_["\"foo   bar\"    a b c \"the d\""
   "\"foo \\\"  bar\"    a b c \"the d\""
   "echo '{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}'"
   "echo 'foo bar'"]

(defn parse-cmd [s]
  (loop [s (java.io.StringReader. s)
         in-double-quotes? false
         in-single-quotes? false
         buf (java.io.StringWriter.)
         parsed []]
    (let [c (.read s)]
      (cond
        (= -1 c) parsed
        (= 39 c) ;; single-quotes
        (if in-single-quotes?
          ;; exit single-quoted string
          (recur s in-double-quotes? false (java.io.StringWriter.) (conj parsed (str buf)))
          ;; enter single-quoted string
          (recur s in-double-quotes? true buf parsed))
        (= 92 c) ;; assume escaped quote
        (let [escaped (.read s)
              buf (doto buf (.write escaped))]
          (recur s in-double-quotes? in-single-quotes? buf parsed))
        (and (not in-single-quotes?) (= 34 c)) ;; double quote
        (if in-double-quotes?
          ;; exit double-quoted string
          (recur s false in-single-quotes? (java.io.StringWriter.) (conj parsed (str buf)))
          ;; enter double-quoted string
          (recur s true in-single-quotes? buf parsed))
        (and (not in-double-quotes?)
             (not in-single-quotes?)
             (Character/isWhitespace c))
        (recur s in-double-quotes? in-single-quotes? (java.io.StringWriter.)
               (let [bs (str buf)]
                 (cond-> parsed
                   (not (str/blank? bs)) (conj bs))))
        :else (do
                (.write buf c)
                (recur s in-double-quotes? in-single-quotes? buf parsed))))))

(defn run-credential-process-cmd [cmd]
  (let [cmd (parse-cmd cmd)
        cmd (if windows?
              (mapv #(str/replace % "\"" "\\\"")
                    cmd)
              cmd)
        ;; _ (binding [*out* *err*] (prn :cmd cmd))
        {:keys [exit out err]} (apply shell/sh cmd)]
    (if (zero? exit)
      out
      (throw (ex-info (str "Non-zero exit: " (pr-str err)) {})))))

(defn get-credentials-via-cmd [cmd]
  (let [credential-map (json/read-str (run-credential-process-cmd cmd))
        {:strs [AccessKeyId SecretAccessKey SessionToken Expiration]} credential-map]
    (assert (and AccessKeyId SecretAccessKey))
    {"aws_access_key_id" AccessKeyId
     "aws_secret_access_key" SecretAccessKey
     "aws_session_token" SessionToken
     :Expiration Expiration}))

(defn -credential-process-credentials-provider
  "Like profile-credentials-provider but with support for credential_process

   See https://github.com/cognitect-labs/aws-api/issues/73"
  ([jvm-props]
   (with-system-properties jvm-props
     (-credential-process-credentials-provider jvm-props (or (u/getenv "AWS_PROFILE")
                                                             (u/getProperty "aws.profile")
                                                             "default"))))
  ([jvm-props profile-name]
   (with-system-properties jvm-props
     (-credential-process-credentials-provider jvm-props profile-name (or (io/file (u/getenv "AWS_CREDENTIAL_PROFILES_FILE"))
                                                                          (io/file (u/getProperty "user.home") ".aws" "credentials")))))
  ([_jvm-props profile-name ^java.io.File f]
   (create-provider
    (creds/auto-refreshing-credentials
     (reify creds/CredentialsProvider
       (fetch [_]
         (when (.exists f)
           (try
             (let [profile (get (config/parse f) profile-name)
                   profile (if-let [cmd (get profile "credential_process")]
                             (merge profile (get-credentials-via-cmd cmd))
                             profile)]
               (creds/valid-credentials
                {:aws/access-key-id     (get profile "aws_access_key_id")
                 :aws/secret-access-key (get profile "aws_secret_access_key")
                 :aws/session-token     (get profile "aws_session_token")
                 ::creds/ttl (creds/calculate-ttl profile)}
                "aws profiles file"))
             (catch Throwable t
               (log/error t "Error fetching credentials from aws profiles file")
               {})))))))))

(def http-client pod.babashka.aws.impl.aws/http-client)

(defn -default-credentials-provider [jvm-props]
  (with-system-properties jvm-props
    (create-provider (creds/default-credentials-provider @http-client))))

(extend-protocol creds/CredentialsProvider
  clojure.lang.PersistentArrayMap
  (fetch [m]
    (creds/fetch (get-provider m))))

;;; Pod Client

(defn -fetch [provider]
  (when-let [provider (get-provider provider)]
    (creds/fetch provider)))

(def lookup-map
  {'fetch -fetch
   '-basic-credentials-provider -basic-credentials-provider
   '-system-property-credentials-provider -system-property-credentials-provider
   '-profile-credentials-provider -profile-credentials-provider
   '-credential-process-credentials-provider -credential-process-credentials-provider
   '-default-credentials-provider -default-credentials-provider})

(require 'cognitect.aws.ec2-metadata-utils)

(def relevant-jvm-properties ["user.home"
                              "aws.profile"
                              "aws.accessKeyId"
                              "aws.secretKey"
                              "aws.sessionToken"
                              "aws.region"
                              cognitect.aws.ec2-metadata-utils/ec2-metadata-service-override-system-property])

(def describe-map
  `{:name pod.babashka.aws.credentials
    :vars
    ~(conj (mapv (fn [[k _]]
                   {:name k})
                 lookup-map)
           {:name "-jvm-properties"
            :code (format
                   "(defn -jvm-properties []
                        (select-keys (System/getProperties) %s))" relevant-jvm-properties)}

           {:name "profile-credentials-provider"
            :code (pr-str
                   '(defn profile-credentials-provider [& args]
                      (apply -profile-credentials-provider (cons (-jvm-properties) args))))}

           {:name "credential-process-credentials-provider"
            :code (pr-str
                   '(defn credential-process-credentials-provider [& args]
                      (apply -credential-process-credentials-provider (cons (-jvm-properties) args))))}

           {:name "basic-credentials-provider"
            :code (pr-str
                   '(defn basic-credentials-provider [conf]
                      (-basic-credentials-provider conf)))}

           {:name "system-property-credentials-provider"
            :code (pr-str
                   '(defn system-property-credentials-provider []
                      (-system-property-credentials-provider (-jvm-properties))))}

           {:name "default-credentials-provider"
            :code (pr-str
                   '(defn default-credentials-provider [& _]
                      (-default-credentials-provider (-jvm-properties))))})})
