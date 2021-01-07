(ns pod.babashka.aws.impl.aws
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [cognitect.aws.client.api :as aws]
   ;; these are dynamically loaded at runtime
   [cognitect.aws.http.cognitect]
   [cognitect.aws.protocols.common]
   [cognitect.aws.protocols.ec2]
   [cognitect.aws.protocols.json]
   [cognitect.aws.protocols.query]
   [cognitect.aws.protocols.rest]
   [cognitect.aws.protocols.rest-json]
   [cognitect.aws.protocols.rest-xml]))

(def http-client (delay (cognitect.aws.http.cognitect/create)))

#_(def *credential-providers (atom {}))

#_(defn get-credential-provider [config]
  (get @*credential-providers (get config ::credential-provider-id)))

(def services (into (sorted-set) (clojure.edn/read-string (slurp (io/resource "aws-services.edn")))))

(def *clients (atom {}))

(defn get-client [config]
  (get @*clients (get config ::client-id)))

(defn client [{:keys [api credentials-provider] :as config}]
  (if-not (contains? services api)
    (throw (ex-info (str "api " api " not available") {:available services}))
    (let [config (assoc config
                        :http-client @http-client)
          client (aws/client config)
          client-id (java.util.UUID/randomUUID)]
      (swap! *clients assoc client-id client)
      {::client-id client-id})))

(defn ops [client]
  (aws/ops (get-client client)))

(defn -doc-str [client op]
  (with-out-str
    (aws/doc (get-client client) op)))

(defn ^bytes input-stream->byte-array [^java.io.InputStream is]
  (let [os (java.io.ByteArrayOutputStream.)]
    (io/copy is os)
    (.toByteArray os)))

(defprotocol IWrapObject
  (wrap-object [x]))

(extend-protocol IWrapObject
  Object
  (wrap-object [x] x)

  java.io.InputStream
  (wrap-object [x]
    {:pod.babashka.aws/wrapped [:bytes (input-stream->byte-array x)]}))

(defn -invoke [client op]
  (walk/postwalk wrap-object (aws/invoke (get-client client) op)))
