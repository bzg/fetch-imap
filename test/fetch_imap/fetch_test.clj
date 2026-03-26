;; Copyright (c) 2026 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns fetch-imap.fetch-test
  "Unit tests for fetch-imap.fetch internals (parse-date, build-search-term).

  These test private functions via var references — no IMAP server required."
  (:require [clojure.test :refer [deftest testing is]]
            [fetch-imap.fetch])
  (:import [java.util Date]
           [jakarta.mail.search SubjectTerm FromStringTerm RecipientStringTerm
            BodyTerm FlagTerm SentDateTerm ReceivedDateTerm AndTerm
            MessageIDTerm]))

(def ^:private parse-date #'fetch-imap.fetch/parse-date)
(def ^:private build-search-term #'fetch-imap.fetch/build-search-term)

;; ---------------------------------------------------------------------------
;; parse-date
;; ---------------------------------------------------------------------------

(deftest parse-date-iso-format
  (testing "yyyy-MM-dd"
    (let [d (parse-date "2025-06-15")]
      (is (instance? Date d))
      (is (= 2025 (+ 1900 (.getYear d))))
      (is (= 5 (.getMonth d)))     ;; June = 5 (zero-based)
      (is (= 15 (.getDate d))))))

(deftest parse-date-iso-datetime-format
  (testing "yyyy-MM-dd'T'HH:mm:ss"
    (let [d (parse-date "2025-06-15T14:30:00")]
      (is (instance? Date d))
      (is (= 2025 (+ 1900 (.getYear d))))
      (is (= 5 (.getMonth d)))
      (is (= 15 (.getDate d))))))

(deftest parse-date-european-format
  (testing "dd/MM/yyyy"
    (let [d (parse-date "15/06/2025")]
      (is (instance? Date d))
      (is (= 2025 (+ 1900 (.getYear d))))
      (is (= 5 (.getMonth d)))
      (is (= 15 (.getDate d))))))

(deftest parse-date-passthrough
  (testing "java.util.Date passes through unchanged"
    (let [now (Date.)]
      (is (identical? now (parse-date now))))))

(deftest parse-date-invalid-string
  (testing "unparseable string throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException (parse-date "not-a-date")))
    (is (thrown? IllegalArgumentException (parse-date "")))
    (is (thrown? IllegalArgumentException (parse-date "yesterday")))))

(deftest parse-date-wrong-type
  (testing "non-string non-Date throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException (parse-date 12345)))
    (is (thrown? IllegalArgumentException (parse-date :keyword)))))

;; ---------------------------------------------------------------------------
;; build-search-term
;; ---------------------------------------------------------------------------

(deftest build-search-term-empty
  (testing "empty criteria returns nil"
    (is (nil? (build-search-term {})))))

(deftest build-search-term-single
  (testing "single :subject returns SubjectTerm"
    (is (instance? SubjectTerm (build-search-term {:subject "hello"}))))

  (testing "single :from returns FromStringTerm"
    (is (instance? FromStringTerm (build-search-term {:from "alice@example.com"}))))

  (testing "single :to returns RecipientStringTerm"
    (is (instance? RecipientStringTerm (build-search-term {:to "bob@example.com"}))))

  (testing "single :body returns BodyTerm"
    (is (instance? BodyTerm (build-search-term {:body "content"}))))

  (testing "single :message-id returns MessageIDTerm"
    (is (instance? MessageIDTerm (build-search-term {:message-id "<abc@example.com>"}))))

  (testing "single :unseen returns FlagTerm"
    (is (instance? FlagTerm (build-search-term {:unseen true}))))

  (testing "single :since returns SentDateTerm"
    (is (instance? SentDateTerm (build-search-term {:since "2025-01-01"}))))

  (testing "single :received-before returns ReceivedDateTerm"
    (is (instance? ReceivedDateTerm (build-search-term {:received-before "2025-12-31"})))))

(deftest build-search-term-multiple
  (testing "multiple criteria returns AndTerm"
    (let [term (build-search-term {:subject "test" :from "alice@example.com"})]
      (is (instance? AndTerm term)))))

(deftest build-search-term-invalid-date
  (testing "invalid date in :since throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException
                (build-search-term {:since "garbage"})))))
