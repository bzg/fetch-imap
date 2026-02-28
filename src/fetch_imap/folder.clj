(ns fetch-imap.folder
  "IMAP folder operations: list, open, close, and query folders."
  (:import [jakarta.mail Folder Store]))

(defn list-folders
  "List all folders on the server. Returns a vector of maps with keys:
   :name, :full-name, :type (:holds-messages, :holds-folders, :holds-both),
   :message-count, :unread-count.

  Example:
    (list-folders conn)"
  [{:keys [^Store store]}]
  (let [default (.getDefaultFolder store)
        folders (.list default "*")]
    (mapv (fn [^Folder f]
            (let [ftype (.getType f)]
              {:name          (.getName f)
               :full-name     (.getFullName f)
               :type          (cond
                                (pos? (bit-and ftype Folder/HOLDS_MESSAGES))
                                (if (pos? (bit-and ftype Folder/HOLDS_FOLDERS))
                                  :holds-both
                                  :holds-messages)
                                (pos? (bit-and ftype Folder/HOLDS_FOLDERS))
                                :holds-folders
                                :else :unknown)
               :message-count (try (.getMessageCount f) (catch Exception _ -1))
               :unread-count  (try (.getUnreadMessageCount f) (catch Exception _ -1))}))
          folders)))

(defn open-folder
  "Open a folder by name. Returns a jakarta.mail.Folder.
  Mode is :readonly (default) or :readwrite.

  Example:
    (open-folder conn \"INBOX\")
    (open-folder conn \"INBOX\" :readwrite)"
  ([conn folder-name] (open-folder conn folder-name :readonly))
  ([{:keys [^Store store]} ^String folder-name mode]
   (let [folder (.getFolder store folder-name)
         m      (case mode
                  :readonly  Folder/READ_ONLY
                  :readwrite Folder/READ_WRITE
                  Folder/READ_ONLY)]
     (.open folder m)
     folder)))

(defn close-folder
  "Close a folder. If expunge is true, permanently removes deleted messages."
  ([^Folder folder] (close-folder folder false))
  ([^Folder folder expunge]
   (when (.isOpen folder)
     (.close folder expunge))))

(defn message-count
  "Return the number of messages in the named folder."
  [{:keys [^Store store]} ^String folder-name]
  (let [folder (.getFolder store folder-name)]
    (try
      (when-not (.isOpen folder)
        (.open folder Folder/READ_ONLY))
      (.getMessageCount folder)
      (finally
        (when (.isOpen folder)
          (.close folder false))))))

(defn unread-count
  "Return the number of unread messages in the named folder."
  [{:keys [^Store store]} ^String folder-name]
  (let [folder (.getFolder store folder-name)]
    (try
      (when-not (.isOpen folder)
        (.open folder Folder/READ_ONLY))
      (.getUnreadMessageCount folder)
      (finally
        (when (.isOpen folder)
          (.close folder false))))))
