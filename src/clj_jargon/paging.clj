(ns clj-jargon.paging
  (:import
   [org.irods.jargon.core.pub.io
    FileIOOperations$SeekWhenceType
    IRODSRandomAccessFile
    IRODSFileFactory]))

(def SEEK-CURRENT (FileIOOperations$SeekWhenceType/SEEK_CURRENT))
(def SEEK-START (FileIOOperations$SeekWhenceType/SEEK_START))
(def SEEK-END (FileIOOperations$SeekWhenceType/SEEK_END))

(def ^:private ^java.nio.charset.Charset utf8 java.nio.charset.StandardCharsets/UTF_8)

(defn ^IRODSRandomAccessFile random-access-file
  [{^IRODSFileFactory file-factory :fileFactory} ^String filepath]
  (.instanceIRODSRandomAccessFile file-factory filepath))

(defn- continuation-byte?
  "True if b is a UTF-8 continuation byte (10xxxxxx)."
  [b]
  (= 0x80 (bit-and (int b) 0xC0)))

(defn- utf8-seq-length
  "Total length of the UTF-8 sequence that lead byte b starts, or nil if b is a
   continuation byte or otherwise not a valid lead byte."
  [b]
  (let [u (bit-and (int b) 0xFF)]
    (cond
      (< u 0x80) 1
      (< u 0xC0) nil
      (< u 0xE0) 2
      (< u 0xF0) 3
      (< u 0xF8) 4
      :else      nil)))

(defn- decode-utf8-chunk
  "Decodes the first len bytes of buffer as UTF-8. Because chunks are read at
   arbitrary byte offsets, a chunk may begin or end in the middle of a multi-byte
   character. Drops a partial multi-byte sequence at the leading edge (only when
   trim-leading? - i.e. we did not read from the start of the file) and an
   incomplete sequence at the trailing edge, so only whole characters are
   returned rather than U+FFFD replacement characters."
  [^bytes buffer len trim-leading?]
  (if (<= len 0)
    ""
    (let [start (if trim-leading?
                  (loop [i 0]
                    (if (and (< i len) (< i 3) (continuation-byte? (aget buffer i)))
                      (recur (inc i))
                      i))
                  0)
          end   (let [j    (loop [i (dec len)]
                             (if (and (> i start) (continuation-byte? (aget buffer i)))
                               (recur (dec i))
                               i))
                      need (when (>= j start) (utf8-seq-length (aget buffer j)))]
                  (if (and need (> (+ j need) len)) j len))]
      (if (< start end)
        (String. buffer (int start) (int (- end start)) utf8)
        ""))))

(defn read-at-position
  ([cm filepath position num-bytes]
   (read-at-position cm filepath position num-bytes true))
  ([cm filepath position num-bytes stringify?]
   (let [access-file (random-access-file cm filepath)
         buffer      (byte-array num-bytes)]
     (let [_   (.seek access-file position SEEK-CURRENT)
           len (.read access-file buffer)
           _   (.close access-file)]
       (if stringify?
         (decode-utf8-chunk buffer len (pos? position))
         (byte-array (take (max 0 len) buffer )))))))

(defn overwrite-at-position
  [cm filepath position update]
  (let [access-file  (random-access-file cm filepath)
        ^bytes update-bytes (.getBytes ^String update)
        read-buffer  (byte-array (count update-bytes))]
    (doto access-file
      (.seek position SEEK-CURRENT)
      (.write update-bytes)
      (.close))
    nil))
