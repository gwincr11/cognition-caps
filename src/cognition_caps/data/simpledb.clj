;;; DataAccess implementation against Amazon SimpleDB
(ns cognition-caps.data.simpledb
  (:use [cognition-caps.data])
  (:require [cognition-caps.config :as config]
            [cemerick.rummage :as sdb]
            [cemerick.rummage.encoding :as enc]))

(def *caps-domain* "items")
(declare change-key marshal-cap merge-large-descriptions unmarshal-cap split-large-descriptions)

(defonce config
  (do
    (.setLevel (java.util.logging.Logger/getLogger "com.amazonaws")
               java.util.logging.Level/WARNING)
    (assoc enc/keyword-strings
           :client (sdb/create-client (get config/db-config "amazon-access-id")
                                      (get config/db-config "amazon-access-key")))))

(defrecord SimpleDBAccess []
  DataAccess
  (get-caps [this]
            (sdb/query-all config '{select * from items}))
  (put-caps [this caps]
      (println "Persisting " (count caps) "caps to SimpleDB")
      (sdb/batch-put-attrs config *caps-domain* (map marshal-cap caps))))
(defn make-SimpleDBAccess [] (SimpleDBAccess.))

(defn- marshal-cap [cap]
  "Preprocesses the given cap before persisting to SimpleDB"
  (split-large-descriptions (change-key :id ::sdb/id cap)))

(defn- unmarshal-cap [cap]
  "Reconstitutes the given cap after reading from SimpleDB"
  (merge-large-descriptions (change-key ::sdb/id :id cap)))

(defn- long-split [re maxlen s]
  "Splits s on the provided regex returning a lazy sequence of substrings of
  up to maxlen each"
  (if (<= (count s) maxlen)
    (vector s)
    (let [matcher (re-matcher re (.substring s 0 (min (count s) maxlen)))]
      (if (re-find matcher)
        (lazy-seq (cons (.substring s 0 (.end matcher)) 
                        (long-split re maxlen (.substring s (.end matcher)))))
        (throw (IllegalStateException. "Can't split the string into substrings, no regex match found"))))))

(defn split-large-descriptions [m]
  "If the given map has a :description value larger than 1024 bytes, it is
  split into multiple integer-suffixed attributes"
  (if (> (count (:description m)) 1024)
    (dissoc
      (reduce (fn [m- descr]
                (assoc (assoc m- (keyword (str "description_" (:_index m-))) descr)
                       :_index (inc (:_index m-))))
              (assoc m :_index 1)
              ; split up the description on whitespace in chunks of up to 1024
              (long-split #"(?:\S++\s++)+" 1024 (:description m)))
      :description :_index)
    m))

(defn merge-large-descriptions [m]
  "If the given map has multiple integer-suffixed :description attributes, they
   are merged into one :description"
  (let [descr-keys (filter #(re-matches #"description_\d+" (name %)) (keys m))
        sorted-keys (sort-by #(Integer. (.substring (name %) (inc (.indexOf (name %) "_")))) descr-keys)]
    (reduce (fn [m- k] (dissoc m- k))
            (assoc m :description (reduce #(str %1 ((keyword %2) m)) "" sorted-keys))
            sorted-keys)))

(defn- change-key [old-key new-key m]
  (dissoc (assoc m new-key (old-key m) old-key)))
