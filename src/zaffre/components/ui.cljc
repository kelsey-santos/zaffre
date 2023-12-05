(ns zaffre.components.ui
  (:require
    [overtone.at-at :as atat]
    [clj-http.client :as client]
    [clojure.java.io :as jio]
    [taoensso.timbre :as log]
    rockpick.core
    [zaffre.components :as zc]
    [zaffre.font :as zfont]
    [zaffre.imageutil :as ziu]))

(log/set-level! :info)

(declare Input)
(declare InputSelect) 

(defn input-element-seq [dom]
  (zc/filter-element-tree (fn [{:keys [type]}]
                            (= type Input))
                          dom))

(defn input-select-element [dom]
  (first
    (zc/filter-element-tree (fn [{:keys [type]}]
							  (= type InputSelect))
							dom)))

(defn to-input-char [event]
  (if (= event :space)
    \ 
    event))

(def InputSelect (zc/create-react-class {
  :display-name "InputSelect"
  :get-initial-state (fn [] {:index 0})
  :get-default-props (fn input-get-default-props [] {
    :on-keypress (fn input-select-on-keypress [this e]
                   (log/info "InputSelect on-keypress" (get e :key))
                   (let [{:keys [key dom]} e
                         {:keys [index]} (zc/state this)
                         input-elements (input-element-seq dom)
                         curr-input (nth input-elements (mod index (count input-elements)))]
                     (if (= key :tab)
                       (let [next-input (nth input-elements (mod (inc index) (count input-elements)))]
                         (when-not (= curr-input next-input)
                           ;; blur curr input
                           (let [instance (zc/construct-instance curr-input)
                                 {:keys [on-blur]} (zc/props instance)]
                             (binding [zc/*current-owner* curr-input]
                               (on-blur
                                 (assoc instance :updater zc/*updater*)
                                 {})))
                           ;; focus next input
                           (let [instance (zc/construct-instance next-input)
                                 {:keys [on-focus]} (zc/props instance)]
                             (binding [zc/*current-owner* next-input]
                               (on-focus
                                 (assoc instance :updater zc/*updater*)
                                 {})))
                           ;; update this state
                           (zc/set-state! this (fn [{:keys [index]}]
                                                 {:index (inc index)}))))
                        ;; dispatch event to curr input
                        (let [instance (zc/construct-instance curr-input)
                              {:keys [on-keypress]} (zc/props instance)]
                          (binding [zc/*current-owner* curr-input]
							(on-keypress
							  (assoc instance :updater zc/*updater*)
							  {:key key}))))))})
  :render
    (fn [this]
      (let [{:keys [children] :as props} (zc/props this)]
        (log/info "InputSelect render")
        (first children)))}))
  
(def Input (zc/create-react-class {
    :display-name "Input"
    :get-initial-state (fn []
                         (log/info "Input get-initial-state")
                         {:value ""
                          :show-cursor false
                          :focused false})
    :component-will-mount (fn [this]
                            (log/info "Input component-will-mount" (get zc/*current-owner* :id))
                            ;; pass current-owner binding through to the scheduled fn
                            (let [owner zc/*current-owner*
                                  updater zc/*updater*
                                  cursor-fn (atat/every 400
                                                        #(try
                                                          (binding [zc/*current-owner* owner
                                                                    zc/*updater* updater]
                                                            (log/info "Input component-will-mount cursor-fn" (get owner :id))
                                                            (zc/set-state! this (fn [{:keys [show-cursor]}]
                                                                                  (log/info "Input component-will-mount cursor set-state-fn" (get owner :id) (not show-cursor))
                                                                                  {:show-cursor (not show-cursor)})))
                                                          (catch Exception e
                                                            (log/error e)))
                                                        zc/*pool*)]
                              (zc/set-state! this {:cursor-fn cursor-fn})))
    :component-will-unmount (fn [this]
                              (let [{:keys [cursor-fn]} (zc/state [this])]
                                (log/info "Input unmounting" cursor-fn)
                                (atat/stop cursor-fn)))
    :get-default-props (fn input-get-default-props [] {
      :max-length 28
      :style {:width 30
              :height 1
              :cursor-char-on \u2592
              :cursor-char-off \space
              :cursor-fg [255 255 255]
              :cursor-bg [0 0 0]}
      :on-focus (fn [this e]
                  (zc/set-state! this (fn [s] (merge s {:focused true}))))
      :on-blur (fn [this e]
                  (zc/set-state! this (fn [s] (merge s {:focused false}))))
      :on-keypress (fn input-on-keypress [this e]
                     (log/info "Input on-keypress" e)
                     (let [{:keys [max-length]} (zc/props this)
                           k (get e :key)]
                       (cond
                         (= k :backspace)
                             (zc/set-state! this (fn [{:keys [value]}]
                                                   {:value (subs value 0 (dec (count value)))}))
                         (and (or (char? k) (= k :space)) (not= k \newline))
                           (zc/set-state! this (fn [{:keys [value]}]
                                                 (if (< (count value) max-length)
                                                   {:value (str value (to-input-char k))}
                                                   {:value value}))))))})
    :render
      (fn [this]
        (let [{:keys [value show-cursor focused]} (zc/state this)
              {:keys [style] :as props} (zc/props this)
              {:keys [cursor-char-on cursor-char-off
                      cursor-fg cursor-bg]}  style
              cursor (if (and focused show-cursor) cursor-char-on cursor-char-off)]
          (log/info "Input render" show-cursor (dissoc props :children))
          (zc/csx [:view {:style {:border-style :single
                                  :border-bottom 1}} [
                    [:text {} [
                      [:text {} [value]]
                      [:text {:style {:fg cursor-fg :bg cursor-bg}} [(str cursor)]]]]]])))}))

(def FileResource (zc/create-react-class {
  :display-name "FileResource"
  :get-initial-state (fn [] {:state :not-loaded})
  :component-will-mount (fn [this]
    (log/info "FileResource component-will-mount" (get zc/*current-owner* :id))
    ;; pass current-owner binding through to the scheduled fn
    (let [owner zc/*current-owner*
          updater zc/*updater*
          {:keys [src child-type]} (zc/props this)]
      (zc/set-state! this {:state :loading})
      (future
        (try
          (binding [zc/*current-owner* owner
                    zc/*updater* updater]
            (log/info "FileResource component-will-mount load-fn" (get owner :id))
            (let [bytes (ziu/slurp-bytes (jio/as-file src))]
              (zc/set-state! this {:state :loaded :data bytes})))
          (catch Exception e
            (log/error e))))))
  :render (fn [this]
    (let [{:keys [src render]} (zc/props this)
          {:keys [state data]} (zc/state this)]
      (log/info "Resource render" src state)
      (case state
         :not-loaded
           (zc/csx [:text {} ["not loaded"]])
         :loading
           (zc/csx [:text {} ["loading"]])
         :loaded
           (render data))))}))

(def UrlResource (zc/create-react-class {
  :display-name "InputSelect"
  :get-initial-state (fn [] {:state :not-loaded})
  :component-will-mount (fn [this]
    (log/info "UrlResource component-will-mount" (get zc/*current-owner* :id))
    ;; pass current-owner binding through to the scheduled fn
    (let [owner zc/*current-owner*
          updater zc/*updater*
          {:keys [src child-type]} (zc/props this)]
      #_(zc/set-state! this {:state :loading})
      (future
        (try
          (binding [zc/*current-owner* owner
                    zc/*updater* updater]
            (log/info "UrlResource component-will-mount load-fn" (get owner :id))
            (let [bytes (client/get src {:as :byte-array})]
              (zc/set-state! this {:state :loaded :data bytes})))
          (catch Exception e
            (log/error e)
            (assert false))))))
  :render (fn [this]
    (let [{:keys [src render]} (zc/props this)
          {:keys [state data]} (zc/state this)]
      (case state
         :not-loaded
           (zc/csx [:text {} ["not loaded"]])
         :loading
           (zc/csx [:text {} ["loading"]])
         :loaded
           (render data))))}))


(def Resource (zc/create-react-class {
  :display-name "InputSelect"
  :render (fn [this]
    (let [{:keys [src render]} (zc/props this)]
      (log/info "Resource render" src)
      (if (clojure.string/starts-with? src "http")
        (zc/csx [UrlResource {:src src :render render}])
        (zc/csx [FileResource {:src src :render render}]))))}))
  
(def NativeImage (zc/create-react-class {
  :display-name "NativeImage"
  :render (fn [this]
    (try
    (let [{:keys [data style]} (zc/props this)
          img (ziu/load-image data)
          clipped-img (if-let [clip (get style :clip)]
                        (ziu/clip-image img clip)
                        img)
          pixels (ziu/pixel-seq clipped-img)
          width (ziu/width clipped-img)
          height (ziu/height clipped-img)
          lines (partition width pixels)]
      ; TODO: close?
      ;(.close img)
      ;(.close clipped-img)
      (log/info "NativeImage render" (get style :clip) width "x" height)
      #_(doseq [line lines]
        (log/info (vec line)))
      (let [img-characters (mapv (fn [[line1 line2]]
                      (mapv (fn [px1 px2]
                             {:c \u2580
                              :fg (conj (mapv (partial bit-and 0xFF) px1) 255)
                              :bg (conj (mapv (partial bit-and 0xFF) px2) 255)}) ; ▀
                           line1 line2))
                    (partition 2 lines))]
        (log/trace "img-characters" img-characters)
        (zc/csx [:img {:width width :height (/ height 2)}
                 img-characters])))
      (catch Exception e
        (log/error e)
        (assert false))))}))

(def RexPaintImage (zc/create-react-class {
  :display-name "RexPaintImage"
  :render (fn [this]
    (let [{:keys [data layer-index]} (zc/props this)
          layer-index (or layer-index 0)
          layers (rockpick.core/read-xp (clojure.java.io/input-stream data))
          layer (nth layers layer-index)
          pixels (mapv (fn [line]
               (log/info "line" line)
               (mapv (fn [{:keys [ch fg bg]}]
                      (let [fg-r (get fg :r)
                            fg-g (get fg :g)
                            fg-b (get fg :b)
                            fg-a 255
                            bg-r (get bg :r)
                            bg-g (get bg :g)
                            bg-b (get bg :b)
                            bg-a 255]
                      {:c (get zfont/cp437-unicode (int ch))
                       :fg [fg-r fg-g fg-b fg-a]
                       :bg [bg-r bg-g bg-b bg-a]}))
                     line))
               layer)]
      (log/info "pixels" pixels)
      (zc/csx [:img {:width (count (first pixels)) :height (count pixels)} pixels])))}))

(def DataImage  (zc/create-react-class {
  :display-name "DataImage"
  :render (fn [this]
    (let [{:keys [src data style]} (zc/props this)]
      (if (clojure.string/ends-with? src "xp")
        (zc/csx [RexPaintImage {:data data :style style}])
        (zc/csx [NativeImage {:data data :style style}]))))}))
  
(def Image (zc/create-react-class {
  :display-name "Image"
  :get-initial-state (fn [] {:state :not-loaded})
  :render (fn [this]
    (let [props (assoc (zc/props this)
                  :render (fn [data]
                            (let [props (assoc (zc/props this) :data data)]
                              (log/trace "Image render-prop fn" (get props :src))
                              (zc/csx [DataImage props]))))]
      (log/trace "Image render" (get props :src))
      ;; passes :src :style, etc through
      (zc/csx [Resource props []])))}))

;; style taken from https://www.nucleo.com.au/using-flexbox-for-modal-dialogs/
(def Popup (zc/create-react-class {
  :display-name "Popup"
  :render (fn [this]
    (log/trace "Popup render")
    (let [{:keys [children] :as props} (zc/props this)]
      (zc/csx [:view {:style {:max-height "100%" :max-width "100%"
                              :height "100%"
                              :align-items :center
                              :justify-content :center
                              :position :fixed
                              :top 0 :left 0
                              :fg [0 0 0 128]
                              :bg [0 0 0 128]}} [
                [:view {:style {:bg [0 0 0 255]
                                :fg [255 255 255 255]
                                :margin-top 10
                                ;:margin-bottom 10
                                :padding 0
                                :border 1 :border-style :single
                                :text-align :center
                                :max-height "90%"
                                :max-width "90%"}}
                    (get props :children)]]])))}))

