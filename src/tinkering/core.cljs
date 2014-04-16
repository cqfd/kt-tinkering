(ns tinkering.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ajax.core :refer [GET]]
            [cljs.core.async :refer [<! >! alts! chan put! sliding-buffer timeout]]))

(enable-console-print!)

(def app-state (atom {:list [] :idx 0}))

(defn yts->movie [m]
  {:title (m "MovieTitleClean")
   :magnet (m "TorrentMagnetUrl")})

(defn tick [ms]
  (let [out (chan (sliding-buffer 1))]
    (go (while true 
          (<! (timeout ms))
          (>! out :tick)))
    out))

;; Lots of different ways to throttle...
(defn throttle [t c]
  (let [out (chan)]
    (go (loop [throttled true]
          (if throttled
            (do (println "Currently throttled...")
                (<! t)
                (println "Just got unthrottled...")
                (recur false))
            (do (println "Currently unthrottled...")
                (>! out (<! c))
                (println "Just got throttled...")
                (recur true)))))
    out))

(defn yts [params]
  (let [ch (chan)]
    (GET "http://yts.re/api/list.json"
         {:params params
          :handler (fn [resp]
                     (put! ch (map yts->movie (resp "MovieList"))))})
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
        :throttle (chan (sliding-buffer 1))})
     om/IWillMount
     (will-mount [this]
       (let [state (om/get-state owner)]
         (go (while true
               (let [_ (println "Waiting for a search string...")
                     search (<! (throttle (:throttle state) (:query state)))
                     _ (println "Just got a search string:" search)
                     movies (<! (yts {:limit 20 :sort "seeds" :keywords search}))]
                 (println "Got some movies!")
                 (om/update! app :list (vec movies)))))))
     om/IRenderState
     (render-state [this state]
       (dom/div nil
         (dom/button #js {:onClick (fn [e] (put! (:throttle state) :please-continue))})
         (dom/input #js {:type "text"
                         :value (:text state)
                         :onChange (fn [e]
                                     (om/set-state! owner :text (.. e -target -value))
                                     (put! (:query state) (.. e -target -value)))})
         (apply dom/ul nil
                (map-indexed (fn [i movie]
                               (dom/li #js {:className (when (= i (:idx app)) "selected")
                                            :onClick (fn [e] (om/update! app :idx i))}
                                       (:title movie)))
                             (:list app)))))))
 app-state
 {:target (. js/document (getElementById "app"))})
