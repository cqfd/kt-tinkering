(ns tinkering.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ajax.core :refer [GET]]
            [cljs.core.async :refer [<! >! alts! chan put! sliding-buffer timeout]]))

(enable-console-print!)

(def app-state (atom {:movies [] :idx 0}))

(defn tick [ms]
  (let [out (chan (sliding-buffer 1))]
    (go (while true 
          (<! (timeout ms))
          (>! out :tick)))
    out))

(defn throttle [t c]
  (let [out (chan)]
    (go (loop [throttled true]
          (if throttled
            (do (<! t) (recur false))
            (do (>! out (<! c)) (recur true)))))
    out))

(defn yts->movie [m]
  {:title (m "MovieTitle")
   :magnet (m "TorrentMagnetUrl")
   :size (m "Size")
   :seeds (m "TorrentSeeds")
   :quality (m "Quality")})

(defn yts [params]
  (let [ch (chan)]
    (println "Trying to search yts:" params)
    (GET "http://yts.re/api/list.json"
         {:params params
          :handler (fn [resp] (put! ch (mapv yts->movie (resp "MovieList"))))})
    ch))

(defn video-widget [data owner]
  (reify
    om/IDidMount
    (did-mount [this]
      (let [node (om/get-node owner)]
        (set! (.-onpause node) (fn [e] (println "Paused!")))))
    om/IRender
    (render [this]
      (dom/video #js {:controls true :src "./godzilla.mp4"}))))

(om/root
 (fn [app owner]
   (reify
     om/IInitState
     (init-state [_]
       {:text ""
        :query (chan (sliding-buffer 1))
        :throttle (chan)})
     om/IWillMount
     (will-mount [this]
       (let [state (om/get-state owner)
             query (throttle (:throttle state) (:query state))]
         (go (while true
               (let [movies (<! (yts {:limit 20 :sort "seeds" :keywords (<! query)}))]
                 (om/update! app :movies movies))))))
     om/IRenderState
     (render-state [this state]
       (dom/div nil
         (dom/button #js {:onClick (fn [e] (put! (:throttle state) :please-continue))} "unthrottle")
         (dom/input #js {:type "text"
                         :value (:text state)
                         :onChange (fn [e]
                                     (println "Search box changed!")
                                     (om/set-state! owner :text (.. e -target -value))
                                     (put! (:query state) (.. e -target -value)))})
         (apply dom/ul nil
                (map-indexed (fn [i movie]
                               (dom/li #js {:className (when (= i (:idx app)) "selected")
                                            :onClick (fn [e] (om/update! app :idx i))}
                                       (str (:title movie) " " (:quality movie) " " (:size movie) " " (:seeds movie))))
                             (:movies app)))))))
 app-state
 {:target (. js/document (getElementById "app"))})
