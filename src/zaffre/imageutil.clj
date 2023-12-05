(ns zaffre.imageutil
  (:require [clojure.java.io :as jio]
            [taoensso.timbre :as log]
            [clojure.test :refer [is]])
  (:import (java.lang AutoCloseable)
           (java.nio ByteBuffer)
           (org.apache.commons.io IOUtils)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFWImage GLFWImage$Buffer)
           (org.lwjgl.stb STBImage STBImageResize STBImageWrite)
           (de.matthiasmann.twl.utils PNGDecoder PNGDecoder$Format)))

(defprotocol Dimensions
  (width [this])
  (height [this]))

(defrecord Image [width height channels byte-buffer]
  Dimensions
  (width [_] width)
  (height [_] height)
  AutoCloseable
  (close [_]
    (STBImage/stbi_image_free byte-buffer)))
  
#_(defmacro close-> [x & forms]
  (close-threaded `(-> ~x ~@forms)))

#_(defn close-threaded
  "Works like img-> but calls (.close) on the input image as well as
   all intermediate images. The result is not automatically closed so
   with-open should be used.
   (with-open [img (load-image \"my.img\")]
     (img-> img
       (scale 2)
       (copy-channel :green)
       (write-png \"out.png\")))"
  {:added "1.0"}
  [f img-form & args]
    (if 
    (reduce (fn [forms form]
      `(with-open [img# ~form]
         ~@forms))
      threaded)))
              
(defn image
  ([width height]
    (image width height 4))
  ([width height channels]
    #_(log/info "Creating buffer" width height channels)
    (->Image width height channels (BufferUtils/createByteBuffer (* width height channels)))))

(defn load-image [location]
  (let [w           (BufferUtils/createIntBuffer 1)
        h           (BufferUtils/createIntBuffer 1)
        c           (BufferUtils/createIntBuffer 1)
        buffer      (->
                      location
                      jio/input-stream
                      IOUtils/toByteArray
                      ByteBuffer/wrap)
        direct-buffer (BufferUtils/createByteBuffer (.limit buffer))]
    (doto direct-buffer
      (.put buffer)
      (.flip))
    (let [byte-buffer (STBImage/stbi_load_from_memory direct-buffer w h c 0)
          width       (.get w)
          height      (.get h)
          channels    (.get c)]
      (log/info "loaded image" location width "x" height channels) 
      (->Image width height channels byte-buffer))))

(defn write-png [{:keys [width height channels byte-buffer] :as img} path]
  (STBImageWrite/stbi_write_png path width height channels byte-buffer 0)
  img)

(defn scale [{:keys [width height channels byte-buffer]} ^long s]
  (let [scaled-bytes (BufferUtils/createByteBuffer (* (* s width)
                                                      (* s height)
                                                      channels))]
    (when (= false
            (STBImageResize/stbir_resize_uint8_generic
              byte-buffer width height 0
              scaled-bytes (* s width) (* s height) 0
              channels
              (if (= channels 4)
                3
                STBImageResize/STBIR_ALPHA_CHANNEL_NONE)
              0
              STBImageResize/STBIR_EDGE_ZERO
              STBImageResize/STBIR_FILTER_BOX
              STBImageResize/STBIR_COLORSPACE_LINEAR))
      (throw (RuntimeException. "Error scaling image")))
    (log/info "scaled-bytes" scaled-bytes)
    (->Image (* s width) (* s height) channels scaled-bytes)))


(defn copy-buffer-range! [dest src n]
  (when (pos? n)
    (let [slice (.limit (.slice src) n)]
      (.put dest slice))))

(defn copy-sub-image [{dwidth :width dheight :height dchannels :channels dbytes :byte-buffer :as dimg}
                      {swidth :width sheight :height schannels :channels sbytes :byte-buffer :as simg}
                      dx1 dy1 sx1 sy1 sx2 sy2]
  {:pre [(is (= dchannels schannels) (format "%s and %s differ in channels" dimg simg))]}
  ;; for each line
  (doseq [y (range (- sy2 sy1))
          :let [sidx (* (+ sx1 (* (+ y sy1) swidth)) schannels)
                didx (* (+ dx1 (* (+ y dy1) dwidth)) dchannels)]
          :when (and (< -1 didx (.limit dbytes))
                     (< -1 sidx (.limit sbytes)))]
      (.position dbytes didx)
      (.position sbytes sidx)
      (copy-buffer-range! dbytes sbytes (* (- sx2 sx1) schannels)))
  (.position sbytes (* swidth sheight schannels))
  (.position dbytes (* dwidth dheight dchannels))
  (.flip sbytes)
  (.flip dbytes)
  dimg)

;; From http://stackoverflow.com/questions/19753134/get-the-points-of-intersection-from-2-rectangles
(defn rect-intersect [x0 y0 x1 y1 x2 y2 x3 y3]
  (let [x4 (max x0 x2)
        y4 (max y0 y2)
        x5 (min x1 x3)
        y5 (min y1 y3)]
    (when (and (< x4 x5)
               (< y4 y5))
      [x4 y4 x5 y5])))

(defn draw-image [{dwidth :width dheight :height :as dest-img}
                  {swidth :width sheight :height :as src-img}
                  x y]
  (let [;; dest image rect
        x0 0
        y0 0
        x1 dwidth
        y1 dheight
        ;; src image rect
        x2 x
        y2 y
        x3 (+ x swidth)
        y3 (+ y sheight)]
    (when-let [[x4 y4 x5 y5] (rect-intersect x0 y0 x1 y1 x2 y2 x3 y3)]
      (let [sx0 (- x4 x)
            sy0 (- y4 y)
            sx1 (- x5 x)
            sy1 (- y5 y)]
        #_(log/info "copying from [" sx0 sy0 "] [" sx1 sy1 "] to" x y)
        (copy-sub-image dest-img src-img x y sx0 sy0 sx1 sy1)))))
                      

(defn resize [{swidth :width sheight :height :as img} width height]
  (copy-sub-image (image width height)
                  img
                  0 0
                  0 0
                  swidth sheight))
(defn- num-channels [k]
  (case k
    :grayscale 1
    :rgb       3
    :rgba      4
    (assert (str "Unknown image type" k))))

(defmulti mode (fn [{:keys [channels]} m]
                 (case [channels m]
                   [1 :grayscale]
                     :nop
                   [1 :rgb]
                     :grayscale->rgb
                   [1 :rgba]
                     :grayscale->rgba
                   [3 :grayscale]
                     :rgb->grayscale
                   [3 :rgb]
                     :nop
                   [3 :rgba]
                     :rgb->rgba
                   [4 :rgb]
                     :rgba->rgb
                   [4 :rgba]
                     :nop)))

(defmethod mode :nop [img _] img)

(defmethod mode :rgb->grayscale
  [{:keys [width height channels byte-buffer]} image-type]
  (let [dest-buffer (BufferUtils/createByteBuffer (* width height (num-channels image-type)))]
    (doseq [i (range (quot (.limit byte-buffer) channels))]
      (let [r (.get byte-buffer)
            g (.get byte-buffer)
            b (.get byte-buffer)]
        (.put dest-buffer (byte (quot (+ r g b) 3)))))
    (.flip byte-buffer)
    (.flip dest-buffer)
    (->Image width height (num-channels image-type) dest-buffer)))

(defmethod mode :grayscale->rgb
  [{:keys [width height channels byte-buffer]} image-type]
  (let [dest-buffer (BufferUtils/createByteBuffer (* width height (num-channels image-type)))]
    (doseq [i (range (.limit byte-buffer))]
      (let [v (.get byte-buffer)]
        (.put dest-buffer v)
        (.put dest-buffer v)
        (.put dest-buffer v)))
    (.flip byte-buffer)
    (.flip dest-buffer)
    (->Image width height (num-channels image-type) dest-buffer)))

(defmethod mode :grayscale->rgba
  [{:keys [width height channels byte-buffer]} image-type]
  (let [dest-buffer (BufferUtils/createByteBuffer (* width height (num-channels image-type)))]
    (doseq [i (range (.limit byte-buffer))]
      (let [v (.get byte-buffer)]
        (.put dest-buffer v)
        (.put dest-buffer v)
        (.put dest-buffer v)
        (.put dest-buffer v)))
    (.flip byte-buffer)
    (.flip dest-buffer)
    (->Image width height (num-channels image-type) dest-buffer)))

(defmethod mode :rgb->rgba
  [{:keys [width height channels byte-buffer]} image-type]
  (log/info "rgb->rgba")
  (let [dest-buffer (BufferUtils/createByteBuffer (* width height (num-channels image-type)))]
    (log/info "dest-buffer" dest-buffer)
    (log/info "pixels" (quot (.limit byte-buffer) channels))
    (doseq [i (range (quot (.limit byte-buffer) channels))]
      (let [r (.get byte-buffer)
            g (.get byte-buffer)
            b (.get byte-buffer)]
        (.put dest-buffer r)
        (.put dest-buffer g)
        (.put dest-buffer b)
        (.put dest-buffer (byte 0))))
    (.flip byte-buffer)
    (.flip dest-buffer)
    (->Image width height (num-channels image-type) dest-buffer)))

(defmethod mode :rgba->rgb
  [{:keys [width height channels byte-buffer]} image-type]
  (let [dest-buffer (BufferUtils/createByteBuffer (* width height (num-channels image-type)))]
    (doseq [i (range (quot (.limit byte-buffer) channels))]
      (let [r (.get byte-buffer)
            g (.get byte-buffer)
            b (.get byte-buffer)
            a (.get byte-buffer)]
        (.put dest-buffer r)
        (.put dest-buffer g)
        (.put dest-buffer b)))
    (.flip byte-buffer)
    (.flip dest-buffer)
    (->Image width height (num-channels image-type) dest-buffer)))

(defn- skip [buffer n]
  (.position buffer (+ (.position buffer) n)))

(defn- copy-channel [v channel]
  (let [channel-shift (case channel
                       :alpha 24
                       :blue  16
                       :green  8
                       :red    0)
        c (->
            (bit-shift-left 0xFF channel-shift)
            (bit-and v)
            (unsigned-bit-shift-right channel-shift))]
    (reduce (fn [a n] (bit-or a (bit-shift-left c n))) 0 [24 16 8 0])))

(defn copy-channels [{:keys [width height channels byte-buffer]} channel]
  (let [img           (image width height)
        result-buffer (BufferUtils/createByteBuffer (* width height channels))]
    (doseq [x (range width)
            y (range height)]
      (let [c (case channel
                :red   0
                :green 1
                :blue  2
                :alpha 3)]
      (skip byte-buffer c)
      (let [v (.get byte-buffer)]
        (dotimes [_ channels]
          (.put result-buffer v)))
      (skip byte-buffer (dec (- channels c)))))
    (.flip byte-buffer)
    (.flip result-buffer)
    (log/info "copy-channels" result-buffer)
    (->Image width height channels result-buffer)))

