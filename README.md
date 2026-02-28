# fetch-imap

A minimal, read-only Clojure library for fetching and parsing IMAP email.

Built on [Eclipse Angus Mail](https://eclipse-ee4j.github.io/angus-mail/) (the modern successor to JavaMail), `fetch-imap` provides a data-oriented API: maps in, maps out.

## Status

Early development — API may change before 1.0.

## Installation

deps.edn:

```clojure
org.clojars.bzg/fetch-imap {:mvn/version "0.1.0"}
```

Leiningen:

```clojure
[org.clojars.bzg/fetch-imap "0.1.0"]
```

## Quick start

```clojure
(require '[fetch-imap.core :as imap]
         '[fetch-imap.fetch :as fetch])

;; Connect and fetch the 5 most recent unread messages
(imap/with-connection [conn {:host "imap.example.com"
                             :user "me@example.com"
                             :password "secret"}]
  (fetch/messages conn "INBOX" {:limit 10 :unseen true}))
```

Each message is returned as a Clojure map:

```clojure
{:uid            12345
 :message-id     "<abc@example.com>"
 :from           [{:name "Alice" :address "alice@example.com"}]
 :to             [{:name "Bob" :address "bob@example.com"}]
 :cc             []
 :bcc            nil
 :reply-to       [{:name "Alice" :address "alice@example.com"}]
 :subject        "Hello from fetch-imap"
 :date-sent      #inst "2025-02-15T10:30:00.000-00:00"
 :date-received  #inst "2025-02-15T10:30:02.000-00:00"
 :content-type   "multipart/alternative; boundary=..."
 :flags          #{:seen}
 :body           {:text "Plain text body"
                  :html "<p>HTML body</p>"
                  :attachments [{:filename "doc.pdf"
                                 :content-type "application/pdf"
                                 :size 14023
                                 :data #object[byte[] ...]}]}
 :headers        {"Subject" "Hello from fetch-imap" ...}}
```

## API

### Connection (`fetch-imap.core`)

```clojure
;; Connect
(def conn (imap/connect {:host "imap.example.com"
                         :port 993
                         :ssl true
                         :user "me@example.com"
                         :password "secret"}))

;; Check connection
(imap/connected? conn) ;; => true

;; Disconnect
(imap/disconnect conn)

;; Or use the macro for automatic cleanup
(imap/with-connection [conn {...}]
  ...)
```

OAuth2 is supported — pass `:oauth2-token` instead of `:password`.

### Fetching messages (`fetch-imap.fetch`)

```clojure
;; Fetch recent messages
(fetch/messages conn "INBOX" {:limit 20})

;; Search with criteria (AND-ed together)
(fetch/messages conn "INBOX" {:from "alice@example.com"
                               :since "2025-01-01"
                               :unseen true})

;; Fetch by UID
(fetch/by-uid conn "INBOX" [12345 12346])

;; Fetch by UID range
(fetch/by-uid-range conn "INBOX" 1000 2000)

;; Lightweight fetch (skip body parsing)
(fetch/messages conn "INBOX" {:limit 100
                               :body? false
                               :headers? false})
```

### Folders (`fetch-imap.folder`)

```clojure
(require '[fetch-imap.folder :as folder])

(folder/list-folders conn)
;; => [{:name "INBOX" :full-name "INBOX" :type :holds-messages
;;      :message-count 1042 :unread-count 3} ...]

(folder/message-count conn "INBOX")  ;; => 1042
(folder/unread-count conn "INBOX")   ;; => 3
```

### IDLE / push notifications (`fetch-imap.idle`)

```clojure
(require '[fetch-imap.idle :as idle])

;; Blocking — run in a future or thread
(def idle-thread
  (idle/idle-async conn "INBOX"
    (fn [msg]
      (println "New message:" (:subject msg)))))

;; Stop with:
(.interrupt idle-thread)
```

## Design principles

- **Read-only** — no sending, writing, moving, or deleting
- **Data-oriented** — all inputs and outputs are plain Clojure maps
- **Minimal API surface** — fewer functions means less to maintain and learn
- **Thin wrapper** — for advanced use cases, interop with Jakarta Mail directly
- **Zero extra dependencies** — only Eclipse Angus Mail, nothing else

## Building & deploying

```bash
# Run tests
clj -X:test cognitect.test-runner.api/test

# Build jar
clj -T:build jar

# Install locally
clj -T:build install

# Deploy to Clojars
clj -T:build deploy
```

## License

Copyright © 2025 Bastien Guerry

Distributed under the Eclipse Public License 2.0.
