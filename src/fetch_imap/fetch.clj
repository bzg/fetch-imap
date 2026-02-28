(ns fetch-imap.fetch
  "Fetch and search IMAP messages.

  All functions return parsed Clojure maps (via fetch-imap.parse/message->map)
  unless :raw? true is passed, in which case raw Jakarta Mail Message objects
  are returned."
  (:require [fetch-imap.folder :as folder]
            [fetch-imap.parse :as parse])
  (:import [jakarta.mail Folder Message UIDFolder UIDFolder$FetchProfileItem
            FetchProfile FetchProfile$Item]
           [jakarta.mail.search AndTerm OrTerm SubjectTerm FromStringTerm
            RecipientStringTerm BodyTerm FlagTerm SentDateTerm ReceivedDateTerm
            ComparisonTerm MessageIDTerm]
           [jakarta.mail Flags Flags$Flag Message$RecipientType]
           [java.util Date]
           [java.text SimpleDateFormat]))

;; ---------------------------------------------------------------------------
;; FetchProfile for efficient batch retrieval
;; ---------------------------------------------------------------------------

(defn- make-fetch-profile
  "Create a FetchProfile to pre-fetch envelope and content info."
  [{:keys [body?] :or {body? true}}]
  (let [fp (FetchProfile.)]
    (.add fp FetchProfile$Item/ENVELOPE)
    (.add fp FetchProfile$Item/FLAGS)
    (when body?
      (.add fp FetchProfile$Item/CONTENT_INFO))
    ;; Pre-fetch UID
    (.add fp UIDFolder$FetchProfileItem/UID)
    fp))

;; ---------------------------------------------------------------------------
;; Search term builders
;; ---------------------------------------------------------------------------

(defn- parse-date
  "Parse a date string or return a Date as-is."
  [d]
  (cond
    (instance? Date d) d
    (string? d)        (let [formats ["yyyy-MM-dd" "yyyy-MM-dd'T'HH:mm:ss" "dd/MM/yyyy"]]
                         (some (fn [fmt]
                                 (try (.parse (SimpleDateFormat. fmt) d)
                                      (catch Exception _ nil)))
                               formats))
    :else              nil))

(defn- build-search-term
  "Build a jakarta.mail.search.SearchTerm from a criteria map.

  Supported keys:
    :subject   - subject contains string
    :from      - from contains string
    :to        - to contains string
    :cc        - cc contains string
    :body      - body contains string
    :message-id - exact Message-ID match
    :unseen    - if true, only unseen messages
    :seen      - if true, only seen messages
    :answered  - if true, only answered messages
    :flagged   - if true, only flagged messages
    :since     - messages sent since date (inclusive)
    :before    - messages sent before date (exclusive)
    :received-since  - messages received since date
    :received-before - messages received before date

  All provided criteria are AND-ed together."
  [criteria]
  (let [terms (cond-> []
                (:subject criteria)
                (conj (SubjectTerm. (:subject criteria)))

                (:from criteria)
                (conj (FromStringTerm. (:from criteria)))

                (:to criteria)
                (conj (RecipientStringTerm. Message$RecipientType/TO (:to criteria)))

                (:cc criteria)
                (conj (RecipientStringTerm. Message$RecipientType/CC (:cc criteria)))

                (:body criteria)
                (conj (BodyTerm. (:body criteria)))

                (:message-id criteria)
                (conj (MessageIDTerm. (:message-id criteria)))

                (:unseen criteria)
                (conj (FlagTerm. (Flags. Flags$Flag/SEEN) false))

                (:seen criteria)
                (conj (FlagTerm. (Flags. Flags$Flag/SEEN) true))

                (:answered criteria)
                (conj (FlagTerm. (Flags. Flags$Flag/ANSWERED) true))

                (:flagged criteria)
                (conj (FlagTerm. (Flags. Flags$Flag/FLAGGED) true))

                (:since criteria)
                (conj (SentDateTerm. ComparisonTerm/GE (parse-date (:since criteria))))

                (:before criteria)
                (conj (SentDateTerm. ComparisonTerm/LT (parse-date (:before criteria))))

                (:received-since criteria)
                (conj (ReceivedDateTerm. ComparisonTerm/GE (parse-date (:received-since criteria))))

                (:received-before criteria)
                (conj (ReceivedDateTerm. ComparisonTerm/LT (parse-date (:received-before criteria)))))]
    (case (count terms)
      0 nil
      1 (first terms)
      (reduce (fn [a b] (AndTerm. a b)) terms))))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- apply-limit
  "Take the last `limit` messages (most recent) from an array."
  [^"[Ljakarta.mail.Message;" msgs limit]
  (if (and limit (< limit (alength msgs)))
    (let [start (- (alength msgs) limit)]
      (java.util.Arrays/copyOfRange msgs start (alength msgs)))
    msgs))

(defn- fetch-and-parse
  "Fetch messages from a folder, apply FetchProfile, and parse."
  [^Folder folder msgs parse-opts]
  (let [fp (make-fetch-profile parse-opts)]
    (.fetch folder msgs fp)
    (mapv #(parse/message->map % parse-opts) msgs)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn messages
  "Fetch messages from a folder.

  Options:
    :limit           - maximum number of messages to return (most recent)
    :subject         - filter by subject (contains)
    :from            - filter by sender (contains)
    :to              - filter by recipient (contains)
    :body            - filter by body text (contains)
    :unseen          - if true, only unseen messages
    :seen            - if true, only seen messages
    :since           - messages sent since date (string or Date)
    :before          - messages sent before date
    :received-since  - messages received since date
    :received-before - messages received before date
    :message-id      - exact Message-ID match
    :headers?        - include all headers in output (default: true)
    :body?           - parse body content (default: true)
    :attachments?    - include attachment byte data (default: true)
    :raw?            - return raw Message objects instead of maps (default: false)

  Returns a vector of message maps (or Message objects if :raw? true).

  Example:
    (messages conn \"INBOX\" {:limit 10 :unseen true})
    (messages conn \"INBOX\" {:since \"2025-01-01\" :from \"alice@example.com\"})"
  ([conn folder-name] (messages conn folder-name {}))
  ([conn folder-name opts]
   (let [folder      (folder/open-folder conn folder-name)
         search-term (build-search-term opts)
         msgs        (if search-term
                       (.search folder search-term)
                       (.getMessages folder))
         msgs        (apply-limit msgs (:limit opts))]
     (try
       (if (:raw? opts)
         (vec msgs)
         (fetch-and-parse folder msgs opts))
       (finally
         (folder/close-folder folder))))))

(defn by-uid
  "Fetch messages by their IMAP UIDs.

  uids can be a single long or a collection of longs.
  Returns a vector of message maps.

  Example:
    (by-uid conn \"INBOX\" 12345)
    (by-uid conn \"INBOX\" [12345 12346 12347])"
  ([conn folder-name uids] (by-uid conn folder-name uids {}))
  ([conn folder-name uids opts]
   (let [folder   (folder/open-folder conn folder-name)
         uid-folder ^UIDFolder folder
         uid-seq  (if (coll? uids) uids [uids])
         msgs     (into-array Message
                              (keep #(try (.getMessageByUID uid-folder (long %))
                                         (catch Exception _ nil))
                                    uid-seq))]
     (try
       (if (:raw? opts)
         (vec msgs)
         (fetch-and-parse folder msgs opts))
       (finally
         (folder/close-folder folder))))))

(defn by-uid-range
  "Fetch messages within a UID range [start, end] inclusive.

  Use UIDFolder/LASTUID as end to fetch from start to the latest message.

  Example:
    (by-uid-range conn \"INBOX\" 1000 2000)
    (by-uid-range conn \"INBOX\" 1000 UIDFolder/LASTUID)"
  ([conn folder-name start end] (by-uid-range conn folder-name start end {}))
  ([conn folder-name start end opts]
   (let [folder     (folder/open-folder conn folder-name)
         uid-folder ^UIDFolder folder
         msgs       (.getMessagesByUID uid-folder (long start) (long end))]
     (try
       (let [valid (into-array Message (remove nil? msgs))]
         (if (:raw? opts)
           (vec valid)
           (fetch-and-parse folder valid opts)))
       (finally
         (folder/close-folder folder))))))
