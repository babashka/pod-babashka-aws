(ns pod.babashka.aws.impl.aws.credentials
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as creds]
   [pod.babashka.aws.impl.aws.credentials]
   [pod.babashka.aws.impl.aws]))

;;; Pod Backend

 (def *providers (atom {}))

(defn create-provider [provider]
  (let [provider-id (java.util.UUID/randomUUID)]
    (swap! *providers assoc provider-id provider)
    {::provider-id provider-id}))

(defn get-provider [config]
  (get @*providers (get config ::provider-id)))

(defn -basic-credentials-provider [conf]
  (create-provider (creds/basic-credentials-provider conf)))

(defn -chain-credentials-provider [providers]
  (create-provider (creds/chain-credentials-provider (map get-provider providers))))

(defn -environment-credentials-provider []
  (create-provider (creds/environment-credentials-provider)))

(defn -profile-credentials-provider
  ([]
   (create-provider (creds/profile-credentials-provider)))

  ([profile-name]
   (create-provider (creds/profile-credentials-provider profile-name)))

  ([profile-name ^java.io.File f]
   (throw (ex-info "profile-credentials-provider with 2 arguments not supported yet" {}))))

(def http-client pod.babashka.aws.impl.aws/http-client)

(defn -container-credentials-provider [__http-client]
  (create-provider (creds/container-credentials-provider @http-client)))

(defn -instance-profile-credentials-provider [__http-client]
  (create-provider (creds/instance-profile-credentials-provider @http-client)))


;;; Pod Client

(defn -fetch [provider]
  (when-let [provider (get-provider provider)]
    (creds/fetch provider)))

(def lookup-map
  {'-http-client http-client
   '-create-provider create-provider
   '-get-provider get-provider
   '-fetch -fetch
   '-basic-credentials-provider -basic-credentials-provider
   '-chain-credentials-provider -chain-credentials-provider
   '-environment-credentials-provider -environment-credentials-provider
   '-profile-credentials-provider -profile-credentials-provider
   '-container-credentials-provider -container-credentials-provider
   '-instance-profile-credentials-provider -instance-profile-credentials-provider
   'valid-credentials creds/valid-credentials})

(def describe-map
  `{:name pod.babashka.aws.credentials
    :vars
    ~(conj (mapv (fn [[k _]]
                   {:name k})
                 lookup-map)
           {:name "fetch"
            :code (pr-str
                   '(defprotocol CredentialsProvider
                      (fetch [_])))}

           {:name "chain-credentials-provider"
            :code (pr-str
                   '(defn chain-credentials-provider [providers]
                      (let [provider (-chain-credentials-provider providers)]
                        (reify CredentialsProvider
                          (fetch [_]
                            (-fetch provider))))))}

           {:name "environment-credentials-provider"
            :code (pr-str
                   '(defn environment-credentials-provider []
                      (let [provider (-environment-credentials-provider)]
                        (reify CredentialsProvider
                          (fetch [_]
                            (-fetch provider))))))}

            {:name "-system-property-credentials-provider"
            :code (pr-str
                   '(defn -system-property-credentials-provider []
                      (-create-provider (when-let [creds (valid-credentials
                                                        {:aws/access-key-id (System/getProperty "aws.accessKeyId")
                                                         :aws/secret-access-key (System/getProperty "aws.secretKey")})]
                                        (-basic-credentials-provider (clojure.set/rename-keys creds  {:aws/access-key-id :access-key-id
                                                                                                      :aws/secret-access-key :secret-access-key}))))))}
           {:name "system-property-credentials-provider"
            :code (pr-str
                   '(defn system-property-credentials-provider []
                      (let [provider (-system-property-credentials-provider)]
                        (reify CredentialsProvider
                          (fetch [_]
                            (-fetch provider))))))}

           ;; See https://github.com/cognitect-labs/aws-api/blob/c1f9393cf6399d35b140307abf96e95ebe6c627f/src/cognitect/aws/credentials.clj#L304
           {:name "default-credentials-provider"
            :code (pr-str
                   '(defn default-credentials-provider [_http-client]
                      (chain-credentials-provider
                       [(-environment-credentials-provider)
                        (-system-property-credentials-provider)
                        (-profile-credentials-provider)
                        (-container-credentials-provider :_dummy-http-client)
                        (-instance-profile-credentials-provider :_dummy-http-client)])))}

           {:name "basic-credentials-provider"
            :code (pr-str
                   '(defn basic-credentials-provider [conf]
                      (let [provider (-basic-credentials-provider conf)]
                        (reify CredentialsProvider
                          (fetch [_]
                            (-fetch provider))))))})})
