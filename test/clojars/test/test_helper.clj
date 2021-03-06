(ns clojars.test.test-helper
  (import java.io.File)
  (:require [clojars.db :as db]
            [clojars.db.migrate :as migrate]
            [clojars.config :refer [config]]
            [clojars.web :as web]
            [korma.db :as kdb]
            [clucy.core :as clucy]
            [clojars.search :as search]
            [clojure.test :as test]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [ring.adapter.jetty :as jetty]))

(def local-repo (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo"))
(def local-repo2 (io/file (System/getProperty "java.io.tmpdir")
                         "clojars" "test" "local-repo2"))

(def test-config {:db {:classname "org.sqlite.JDBC"
                       :subprotocol "sqlite"
                       :subname "data/test/db"}
                  :key-file "data/test/authorized_keys"
                  :repo "data/test/repo"
                  :event-dir "data/test/events"
                  :stats-dir "data/test/stats"
                  :index-path "data/test/index"
                  :bcrypt-work-factor 12
                  :mail {:hostname "smtp.gmail.com"
                         :from "noreply@clojars.org"
                         :username "clojars@pupeno.com"
                         :password "fuuuuuu"
                         :port 465 ; If you change ssl to false, the port might not be effective, search for .setSSL and .setSslSmtpPort
                         :ssl true}})

(defn using-test-config [f]
  (with-redefs [config test-config]
    (f)))

(defn delete-file-recursively
  "Delete file f. If it's a directory, recursively delete all its contents."
  [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-file-recursively child)))
      (io/delete-file f))))

(defonce migrate
  (delay
   (let [db (:subname (:db config))]
     (when-not (.exists (io/file db))
       (.mkdirs (.getParentFile (io/file db)))
       (sh/sh "sqlite3" db :in (slurp "clojars.sql"))))
   (migrate/-main)))

(defn make-download-count! [m]
  (spit (io/file (config :stats-dir) "all.edn")
        (pr-str m)))

(defn make-index! [v]
  (delete-file-recursively (io/file (config :index-path)))
  (with-open [index (clucy/disk-index (config :index-path))]
    (if (empty? v)
      (do (clucy/add index {:dummy true})
          (clucy/search-and-delete index "dummy:true"))
      (binding [clucy/*analyzer* search/analyzer]
        (doseq [a v]
          (clucy/add index a))))))

(defn default-fixture [f]
  (using-test-config
   (fn []
     (force migrate)
     (delete-file-recursively (io/file (config :repo)))
     (delete-file-recursively (io/file (config :event-dir)))
     (.mkdirs (io/file (config :event-dir)))
     (delete-file-recursively (io/file (config :stats-dir)))
     (.mkdirs (io/file (config :stats-dir)))
     (make-download-count! {})
     (with-redefs [kdb/_default (atom {:pool (:db config)})]
       (jdbc/with-connection (:pool @kdb/_default)
         (jdbc/do-commands
          "delete from users;" "delete from jars;" "delete from groups;"))
       (f)))))

(defn index-fixture [f]
  (make-index! [])
  (f))

(declare test-port)

(defn run-test-app
  ([f]
   (run-test-app nil f))
  ([verbose? f]
   (let [server (jetty/run-jetty
                  (if verbose?
                    #'web/clojars-app
                    #(binding [*out* (java.io.StringWriter.)]
                       (#'web/clojars-app %)))
                  {:port 0 :join? false})
         port (-> server .getConnectors first .getLocalPort)]
     (with-redefs [test-port port]
       (try
         (f)
         (finally
           (.stop server)))))))
