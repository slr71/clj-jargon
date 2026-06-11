(ns clj-jargon.test.paging
  (:use [clojure.test])
  (:require [clj-jargon.paging]
            [clojure.string :as string]))

;; decode-utf8-chunk is private; pull it (and its helpers) into this namespace for testing.
(def decode-utf8-chunk #'clj-jargon.paging/decode-utf8-chunk)

(def ^:private ^java.nio.charset.Charset utf8 java.nio.charset.StandardCharsets/UTF_8)

(defn- ->bytes
  "UTF-8 encodes s into a byte array."
  ^bytes [^String s]
  (.getBytes s utf8))

(defn- cp-count
  "Number of Unicode code points in s (so a 4-byte/surrogate-pair char counts as one)."
  [^String s]
  (.codePointCount s 0 (.length s)))

(defn- decode
  "Convenience wrapper: decode the whole byte array as a chunk."
  ([^bytes ba trim-leading?]
   (decode ba (alength ba) trim-leading?))
  ([^bytes ba len trim-leading?]
   (decode-utf8-chunk ba len trim-leading?)))

;; Sample strings exercising 1-, 2-, 3- and 4-byte UTF-8 sequences.
(def ^:private ascii "hello, world")
(def ^:private accented "café crème")          ; 2-byte é
(def ^:private cjk "日本語テキスト")            ; 3-byte chars
(def ^:private emoji "smile 😀 here") ; U+1F600, 4-byte

(deftest ascii-decodes-unchanged
  (is (= ascii (decode (->bytes ascii) false)))
  (is (= ascii (decode (->bytes ascii) true)))
  (is (= "" (decode (->bytes "") false))))

(deftest non-positive-len-is-empty
  (is (= "" (decode (->bytes ascii) 0 false)))
  (is (= "" (decode (->bytes ascii) -1 false))))

(deftest leading-partial-char-dropped-only-when-trimming
  ;; "é" is 0xC3 0xA9. Starting the chunk one byte in leaves a lone continuation byte.
  (let [tail (java.util.Arrays/copyOfRange (->bytes "é!") 1 3)] ; [0xA9 0x21]
    ;; trim-leading? true: the dangling continuation byte is dropped, leaving "!".
    (is (= "!" (decode tail true)))
    ;; trim-leading? false: nothing trimmed at the front; the decoder replaces the bad byte.
    (is (string/ends-with? (decode tail false) "!"))))

(deftest trailing-incomplete-char-dropped
  ;; Whole string minus its last byte leaves an incomplete final multi-byte char.
  (doseq [s [accented cjk emoji]]
    (let [full (->bytes s)
          truncated (java.util.Arrays/copyOfRange full 0 (dec (alength full)))
          decoded (decode truncated false)]
      ;; No replacement character, and the result is a proper prefix of the original.
      (is (not (string/includes? decoded "�")) (str "no U+FFFD for " s))
      (is (string/starts-with? s decoded) (str "prefix of " s)))))

(deftest split-on-char-boundary-is-intact
  ;; "café" -> bytes; "caf" is 3 bytes, then é. Splitting at byte 3 is a clean boundary.
  (let [full (->bytes "café")
        head (java.util.Arrays/copyOfRange full 0 3)
        tail (java.util.Arrays/copyOfRange full 3 (alength full))]
    (is (= "caf" (decode head false)))
    (is (= "é" (decode tail true)))))

(deftest round-trip-at-every-offset
  ;; For each multi-byte string, split its UTF-8 bytes at every offset into [head tail],
  ;; decode head (start of file) and tail (mid-file), and assert the concatenation drops at
  ;; most the single character straddling the cut and never introduces U+FFFD.
  (doseq [s [accented cjk emoji "aé本😀z"]]
    (let [full (->bytes s)
          n    (alength full)]
      (doseq [cut (range 0 (inc n))]
        (let [head    (java.util.Arrays/copyOfRange full (int 0) (int cut))
              tail    (java.util.Arrays/copyOfRange full (int cut) (int n))
              decoded (str (decode head false) (decode tail true))]
          (is (not (string/includes? decoded "�"))
              (str "no U+FFFD for " (pr-str s) " cut at " cut))
          ;; decoded is s with at most one character removed (the one spanning the cut).
          (is (<= (- (cp-count s) (cp-count decoded)) 1)
              (str "at most one char dropped for " (pr-str s) " cut at " cut)))))))
