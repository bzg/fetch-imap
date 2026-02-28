(ns fetch-imap.idle
  "IMAP IDLE support for receiving push notifications when new messages arrive.

  IDLE keeps a connection open and the server notifies the client when
  the folder state changes (new messages, deletions, flag changes)."
  (:require [fetch-imap.folder :as folder]
            [fetch-imap.parse :as parse])
  (:import [jakarta.mail Folder Store MessageCountListener]
           [jakarta.mail.event MessageCountEvent]))

(defn- make-message-listener
  "Create a MessageCountListener that calls on-added when new messages arrive."
  [on-added parse-opts]
  (reify MessageCountListener
    (messagesAdded [_ event]
      (let [msgs (.getMessages ^MessageCountEvent event)]
        (doseq [msg msgs]
          (try
            (on-added (parse/message->map msg parse-opts))
            (catch Exception e
              (println "Error processing new message:" (.getMessage e)))))))
    (messagesRemoved [_ _event]
      ;; We don't act on removals in a read-only library
      nil)))

(defn idle
  "Start an IDLE loop on a folder, calling on-message for each new message.

  This function blocks the current thread. It will automatically re-issue
  the IDLE command when it times out (servers typically time out after ~29 min).

  Options:
    :parse-opts  - options to pass to message->map (default: {})
    :on-error    - function called with Exception on errors (default: prints)
    :keep-alive-ms - interval to re-issue IDLE if the server doesn't
                     support indefinite IDLE (default: 1680000 = 28 min)

  Returns nil when the folder or store is closed, or when the thread
  is interrupted.

  Example:
    (future
      (idle conn \"INBOX\"
            (fn [msg] (println \"New:\" (:subject msg)))
            {:parse-opts {:attachments? false}}))"
  ([conn folder-name on-message] (idle conn folder-name on-message {}))
  ([conn folder-name on-message {:keys [parse-opts on-error keep-alive-ms]
                                  :or   {parse-opts    {}
                                         on-error      #(println "IDLE error:" (.getMessage %))
                                         keep-alive-ms 1680000}}]
   (let [folder   (folder/open-folder conn folder-name)
         listener (make-message-listener on-message parse-opts)]
     (.addMessageCountListener folder listener)
     (try
       (loop []
         (when (and (.isOpen folder)
                    (.isConnected ^Store (:store conn))
                    (not (Thread/interrupted)))
           (try
             ;; IDLE command — blocks until server sends a notification
             ;; or the keep-alive timeout is reached.
             ;; The folder must support IDLE (most modern IMAP servers do).
             (let [imap-folder ^org.eclipse.angus.mail.imap.IMAPFolder folder]
               (.idle imap-folder))
             (catch org.eclipse.angus.mail.imap.IMAPFolder$IdleFailedException _
               ;; Server doesn't support IDLE — fall back to polling
               (Thread/sleep keep-alive-ms)
               ;; Force a NOOP to check for new messages
               (.getMessageCount folder))
             (catch jakarta.mail.FolderClosedException _
               nil)  ;; Exit the loop
             (catch Exception e
               (on-error e)
               (Thread/sleep 5000)))
           (recur)))
       (finally
         (.removeMessageCountListener folder listener)
         (folder/close-folder folder))))))

(defn idle-async
  "Start IDLE in a new daemon thread. Returns the Thread object.

  Call (.interrupt thread) to stop the IDLE loop.

  Example:
    (def idle-thread
      (idle-async conn \"INBOX\"
                  (fn [msg] (println \"New:\" (:subject msg)))))
    ;; Later:
    (.interrupt idle-thread)"
  ([conn folder-name on-message] (idle-async conn folder-name on-message {}))
  ([conn folder-name on-message opts]
   (let [t (Thread. ^Runnable (fn [] (idle conn folder-name on-message opts))
                    (str "fetch-imap-idle-" folder-name))]
     (.setDaemon t true)
     (.start t)
     t)))
