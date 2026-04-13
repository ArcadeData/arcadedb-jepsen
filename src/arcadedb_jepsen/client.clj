(ns arcadedb-jepsen.client
  "HTTP client for ArcadeDB REST API."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :refer [info warn]])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]))

(def ^:dynamic *leader-cache*
  "Atom holding the current known leader node name, or nil."
  (atom nil))

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
        ^HttpResponse response (.send ^HttpClient (:http-client client)
                                      request (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        body-str (str (.body response))]
    (when (>= status 400)
      (throw (ex-info (str "ArcadeDB HTTP " status ": " body-str)
                      {:status status :body body-str})))
    (json/parse-string body-str true)))

(defn find-leader
  "Queries each node's cluster info to find the current leader.
   Returns the leader node name, or nil if no leader found."
  [nodes opts]
  (let [port     (:http-port opts 2480)
        password (:password opts)
        auth     (basic-auth "root" password)
        http     (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) (.build))]
    (first
      (for [node nodes
            :let [url (str "http://" (name node) ":" port "/api/v1/server?mode=cluster")
                  req (-> (HttpRequest/newBuilder)
                          (.uri (URI/create url))
                          (.header "Authorization" auth)
                          (.GET)
                          (.timeout (Duration/ofSeconds 5))
                          (.build))]
            :when (try
                    (let [^HttpResponse resp (.send http req (HttpResponse$BodyHandlers/ofString))
                          body (json/parse-string (str (.body resp)) true)]
                      (get-in body [:ha :isLeader]))
                    (catch Exception _ false))]
        node))))

(defn leader-client
  "Creates an HTTP client connected to the current leader.
   Retries with backoff during elections (up to ~10s) before giving up."
  [test opts]
  (loop [attempt 0]
    (let [leader (find-leader (:nodes test) opts)]
      (if leader
        (make-client leader opts)
        (when (< attempt 4)
          (Thread/sleep (* 1000 (inc attempt)))
          (recur (inc attempt)))))))

(defn invalidate-leader!
  "Clears the cached leader so the next call to leader-client re-discovers it."
  []
  (reset! *leader-cache* nil))

(defn query!
  "Executes a SQL query (read-only) on ArcadeDB."
  [client command]
  (let [url  (str (:base-url client) "/api/v1/query/" (:database client)
                  "/sql/" (java.net.URLEncoder/encode ^String command "UTF-8"))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Authorization" (:auth client))
                    (.GET)
                    (.timeout (Duration/ofSeconds 10))
                    (.build))
        ^HttpResponse response (.send ^HttpClient (:http-client client)
                                      request (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        body-str (str (.body response))]
    (when (>= status 400)
      (throw (ex-info (str "ArcadeDB HTTP " status ": " body-str)
                      {:status status :body body-str})))
    (json/parse-string body-str true)))
