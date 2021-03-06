(ns ora.line
  (:require [clojure.core.async :as a
             :refer [>! <! >!! <!! go chan buffer
                     close! thread alts! alts!! timeout]])
  (:import [javax.sound.sampled
            TargetDataLine
            SourceDataLine
            DataLine
            DataLine$Info
            Port
            Port$Info
            AudioFormat
            AudioSystem
            Mixer
            Mixer$Info]
           [java.io ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defrecord LineIn [out stop-flag _agent])

(defn create-audio-format
  ^AudioFormat
  []
  (AudioFormat. 44100 ; rate
                16 ; size
                1 ; channels
                true ; signed
                false)) ; big-endian

(defn create-data-line-info
  ^DataLine$Info
  [direction audio-format]
  (let [data-lines {:output SourceDataLine
                    :input TargetDataLine}]
    (DataLine$Info. (data-lines direction) audio-format)))

(defn get-target-data-line
  ^TargetDataLine
  ([^DataLine$Info info]
   (AudioSystem/getLine info))
  ([^Mixer mixer
    ^DataLine$Info info]
   (.getLine mixer info)))

(defn get-source-data-line
  ^SourceDataLine
  ([^DataLine$Info info]
   (AudioSystem/getLine info))
  ([^Mixer mixer
    ^DataLine$Info info]
   (.getLine mixer info)))

;; Still need to get stuff selected by audioformat
;; (vec (.getFormats (aget (.getTargetLineInfo default-mixer) 0)))

;; Get data line from default device/mixer:
;; (.getLine (get-default-mixer) (create-data-line-info :source af))

;; (def target-data-line (get-target-data-line default-mixer
;;                                             (create-data-line-info :input (create-audio-format))))

;; then need to open and start

(defn get-mixer-by-name
  ^Mixer
  [mixer-name]
  (->> (AudioSystem/getMixerInfo)
       (drop-while (fn [^Mixer$Info info] (not= mixer-name
                                                (.getName info))))
       first
       (AudioSystem/getMixer)))

(defn survey-audio
  []
  (let [mixer-infos (AudioSystem/getMixerInfo)]
    (for [^Mixer$Info mixer-info mixer-infos]
      (let [^Mixer mixer (AudioSystem/getMixer mixer-info)]
        {:mixer mixer
         :info mixer-info
         :name (.getName mixer-info)
         :vendor (.getVendor mixer-info)
         :version (.getVendor mixer-info)
         :description (.getDescription mixer-info)
         :target-lines (map (fn [line-info]
                              (let [line (.getLine mixer line-info)
                                    line-type (condp instance? line
                                                DataLine
                                                :data-line

                                                Port
                                                :port)
                                    line-info (case line-type
                                                :data-line (cast DataLine$Info line-info)
                                                :port (cast Port$Info line-info))]
                                {:info line-info
                                 :line line
                                 :type line-type
                                 :desc (str line-info)}))
                            (.getTargetLineInfo mixer))
         :source-lines (map (fn [line-info]
                              (let [line (.getLine mixer line-info)
                                    line-type (condp instance? line
                                                DataLine
                                                :data-line

                                                Port
                                                :port)
                                    line-info (case line-type
                                                :data-line (cast DataLine$Info line-info)
                                                :port (cast Port$Info line-info))]
                                {:info line-info
                                 :line line
                                 :type line-type
                                 :desc (str line-info)}))
                            (.getSourceLineInfo mixer))}))))

(defn print-survey
  [survey-info]
  (doseq [mixer survey-info]
    (println "Mixer")
    (clojure.pprint/pprint (select-keys mixer [:name :vendor :version :description]))
    (println "Target Lines")
    (clojure.pprint/pprint (map (juxt :type :desc) (:target-lines mixer)))
    (println "Source Lines")
    (clojure.pprint/pprint (map (juxt :type :desc) (:source-lines mixer)))
    (println (clojure.string/join (repeat 10 "=")))))

(defn get-default-mixer
  ^Mixer
  []
  (get-mixer-by-name "Default Audio Device"))

(defn get-builtin-mixer
  ^Mixer
  []
  (get-mixer-by-name "Built-in Microphone"))

(defn get-inport-mixer
  ^Mixer
  []
  (get-mixer-by-name "Port Built-in Microphone"))

(defn read-line-in
  [a
   stop-flag
   ^TargetDataLine _data-line
   ^bytes _buf]
  (.open _data-line)
  (.start _data-line)
  (loop [data-line _data-line
         buf _buf]
    (if-not @stop-flag
      (do
        (let [num-bytes-read (.read data-line buf 0 (alength buf))]
          ;;(.write ^ByteArrayOutputStream (a :out) buf 0 num-bytes-read)
          (doseq [idx (range num-bytes-read)]
            ;; Should use "put!"?
            (a/>!! (a :out) (aget buf idx))))
        (recur data-line buf))
      (do
        (.flush data-line)
        (.stop data-line)
        (a :stopped true)))))

(defn start-read-line-agent
  [^TargetDataLine data-line
   ;;^ByteArrayOutputStream out
   out
   stop-flag]
  (send-off (agent {:stopped false
                    :out out
                    :data-line data-line})
            read-line-in
            stop-flag
            data-line
            (byte-array 8192)))

(defn stop-capture-line-in
  [line-in]
  (swap! (:stop-flag line-in) (constantly true)))

(defn start-capture-line-in
  []
  (let [input-line (get-target-data-line (get-default-mixer)
                                         (create-data-line-info :input (create-audio-format)))
        ;;out (ByteArrayOutputStream. 16384)
        out (chan (a/sliding-buffer 16834))
        stop-flag (atom false)]
    (LineIn. out stop-flag (start-read-line-agent input-line out stop-flag))))
