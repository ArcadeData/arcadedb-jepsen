(ns arcadedb-jepsen.client
  "HTTP client for ArcadeDB REST API."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :refer [info warn]])
  (:import [java.net URI HttpURLConnection]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]))

(defn basic-auth
  "Returns the Basic auth header value."
  [user password]
  (str "Basic "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (str user ":" password) "UTF-8"))))

(defn make-client
  "Creates an HTTP client map for a specific ArcadeDB node."
  [node opts]
  (let [port     (:http-port opts 2480)
        user     (:user opts "root")
        password (:password opts)
        base-url (str "http://" (name node) ":" port)]
    {:http-client (-> (HttpClient/newBuilder)
                      (.connectTimeout (Duration/ofSeconds 5))
                      (.build))
     :base-url    base-url
     :auth        (basic-auth user password)
     :database    (:database opts "jepsen")}))

(defn command!
  "Executes a SQL command on ArcadeDB. Returns the parsed JSON response.
   Throws on HTTP errors or connection failures."
  [client language command & [params]]
  (let [url  (str (:base-url client) "/api/v1/command/" (:database client))
        body (cond-> {:language language :command command}
               params (assoc :params params))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Authorization" (:auth client))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        response (.send (:http-client client) request
                        (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        body-str (.body response)]
    (when (>= status 400)
      (throw (ex-info (str "ArcadeDB HTTP " status ": " body-str)
                      {:status status :body body-str})))
    (json/parse-string body-str true)))

(defn query!
  "Executes a SQL query (read-only) on ArcadeDB."
  [client command]
  (let [url  (str (:base-url client) "/api/v1/query/" (:database client)
                  "/sql/" (java.net.URLEncoder/encode command "UTF-8"))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Authorization" (:auth client))
                    (.GET)
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        response (.send (:http-client client) request
                        (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        body-str (.body response)]
    (when (>= status 400)
      (throw (ex-info (str "ArcadeDB HTTP " status ": " body-str)
                      {:status status :body body-str})))
    (json/parse-string body-str true)))
