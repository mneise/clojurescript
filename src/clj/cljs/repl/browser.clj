;; Copyright (c) Rich Hickey. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns cljs.repl.browser
  (:refer-clojure :exclude [loaded-libs])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cljs.compiler :as comp]
            [cljs.util :as util]
            [cljs.env :as env]
            [cljs.closure :as cljsc]
            [cljs.repl :as repl]
            [cljs.repl.server :as server])
  (:import [java.util.regex Pattern]))

(defonce browser-state
  (atom {:return-value-fn nil
         :client-js nil}))

(def loaded-libs (atom #{}))

(def preloaded-libs (atom #{}))

(defn- set-return-value-fn
  "Save the return value function which will be called when the next
  return value is received."
  [f]
  (swap! browser-state (fn [old] (assoc old :return-value-fn f))))

(defn send-for-eval
  "Given a form and a return value function, send the form to the
  browser for evaluation. The return value function will be called
  when the return value is received."
  ([form return-value-fn]
    (send-for-eval @(server/connection) form return-value-fn))
  ([conn form return-value-fn]
    (set-return-value-fn return-value-fn)
    (server/send-and-close conn 200 form "text/javascript")))

(defn- return-value
  "Called by the server when a return value is received."
  [val]
  (when-let [f (:return-value-fn @browser-state)]
    (f val)))

(defn repl-client-js []
  (slurp (:client-js @browser-state)))

(defn send-repl-client-page
  [request conn opts]
  (server/send-and-close conn 200
    (str "<html><head><meta charset=\"UTF-8\"></head><body>
          <script type=\"text/javascript\">"
         (repl-client-js)
         "</script>"
         "<script type=\"text/javascript\">
          clojure.browser.repl.client.start(\"http://" (-> request :headers :host) "\");
          </script>"
         "</body></html>")
    "text/html"))

(defn send-static [{path :path :as request} conn opts]
  (if (and (:static-dir opts)
           (not= "/favicon.ico" path))
    (let [path   (if (= "/" path) "/index.html" path)
          st-dir (:static-dir opts)
          local-path
          (cond->
            (seq (for [x (if (string? st-dir) [st-dir] st-dir)
                       :when (.exists (io/file (str x path)))]
                   (str x path)))
            (complement nil?) first)
          local-path
          (if (nil? local-path)
            (cond
              (re-find #".jar" path)
              (io/resource (second (string/split path #".jar!/")))
              (re-find (Pattern/compile (System/getProperty "user.dir")) path)
              (io/file (string/replace path (str (System/getProperty "user.dir") "/") ""))
              :else nil)
            local-path)]
      (if local-path
        (server/send-and-close conn 200 (slurp local-path)
          (condp #(.endsWith %2 %1) path
            ".html" "text/html"
            ".css" "text/css"
            ".html" "text/html"
            ".jpg" "image/jpeg"
            ".js" "text/javascript"
            ".cljs" "text/x-clojure"
            ".map" "application/json"
            ".png" "image/png"
            "text/plain"))
        (server/send-404 conn path)))
    (server/send-404 conn path)))

(server/dispatch-on :get
  (fn [{:keys [path]} _ _]
    (.startsWith path "/repl"))
  send-repl-client-page)

(server/dispatch-on :get
  (fn [{:keys [path]} _ _]
    (or
      (= path "/")
      (.endsWith path ".js")
      (.endsWith path ".cljs")
      (.endsWith path ".map")
      (.endsWith path ".html")
      (.endsWith path ".css")))
  send-static)

(defmulti handle-post (fn [m _ _ ] (:type m)))

(server/dispatch-on :post (constantly true) handle-post)

(def ordering (agent {:expecting nil :fns {}}))

(defmethod handle-post :ready [_ conn _]
  (reset! loaded-libs @preloaded-libs)
  (send ordering (fn [_] {:expecting nil :fns {}}))
  (send-for-eval conn
    (cljsc/-compile
      '[(set! *print-fn* clojure.browser.repl/repl-print)] {})
    identity))

(defn add-in-order [{:keys [expecting fns]} order f]
  {:expecting (or expecting order)
   :fns (assoc fns order f)})

(defn run-in-order [{:keys [expecting fns]}]
  (loop [order expecting fns fns]
    (if-let [f (get fns order)]
      (do
        (f)
        (recur (inc order) (dissoc fns order)))
      {:expecting order :fns fns})))

(defn constrain-order
  "Elements to be printed in the REPL will arrive out of order. Ensure
  that they are printed in the correct order."
  [order f]
  (send-off ordering add-in-order order f)
  (send-off ordering run-in-order))

(defmethod handle-post :print [{:keys [content order]} conn _ ]
  (constrain-order order
    (fn []
      (print (read-string content))
      (.flush *out*)))
  (server/send-and-close conn 200 "ignore__"))

(defmethod handle-post :result [{:keys [content order]} conn _ ]
  (constrain-order order
    (fn []
      (return-value content)
      (server/set-connection conn))))

(defn browser-eval
  "Given a string of JavaScript, evaluate it in the browser and return a map representing the
   result of the evaluation. The map will contain the keys :type and :value. :type can be
   :success, :exception, or :error. :success means that the JavaScript was evaluated without
   exception and :value will contain the return value of the evaluation. :exception means that
   there was an exception in the browser while evaluating the JavaScript and :value will
   contain the error message. :error means that some other error has occured."
  [form]
  (let [return-value (promise)]
    (send-for-eval form
      (fn [val] (deliver return-value val)))
    (let [ret @return-value]
      (try
        (read-string ret)
        (catch Exception e
          {:status :error
           :value (str "Could not read return value: " ret)})))))

(defn load-javascript
  "Accepts a REPL environment, a list of namespaces, and a URL for a
  JavaScript file which contains the implementation for the list of
  namespaces. Will load the JavaScript file into the REPL environment
  if any of the namespaces have not already been loaded from the
  ClojureScript REPL."
  [repl-env provides url]
  (let [missing (remove #(contains? @loaded-libs %) provides)]
    (when (seq missing)
      (browser-eval (slurp url))
      (swap! loaded-libs (partial apply conj) missing))))

(defn setup [repl-env opts]
  (server/start repl-env))

;; =============================================================================
;; Stracktrace parsing

(defmulti parse-stacktrace (fn [repl-env st err opts] (:ua-product err)))

(defmethod parse-stacktrace :default
  [repl-env st err opts] st)

(defn parse-file-line-column [flc]
  (let [xs (string/split flc #":")
        [pre [line column]]
        (reduce
          (fn [[pre post] [x i]]
            (if (<= i 2)
              [pre (conj post x)]
              [(conj pre x) post]))
          [[] []] (map vector xs (range (count xs) 0 -1)))
        file (string/join ":" pre)]
    [(cond-> file
       (.startsWith file "(") (string/replace "(" ""))
     (Long/parseLong
       (cond-> line
         (.endsWith line ")") (string/replace ")" "")))
     (Long/parseLong
       (cond-> column
         (.endsWith column ")") (string/replace ")" "")))]))

(defn parse-file [file opts]
  (if (re-find #"http://localhost:9000/" file)
    (-> file
      (string/replace #"http://localhost:9000/" "")
      (string/replace (Pattern/compile (str "^" (util/output-directory opts) "/")) ""))
    (if-let [asset-root (:asset-root opts)]
      (string/replace file asset-root "")
      (throw
        (ex-info (str "Could not relativize URL " file)
          {:type :parse-stacktrace
           :reason :relativize-url})))))

;; -----------------------------------------------------------------------------
;; Chrome Stacktrace

(defn chrome-st-el->frame
  [st-el opts]
  (let [xs (-> st-el
             (string/replace #"\s+at\s+" "")
             (string/split #"\s+"))
        [function flc] (if (== (count xs) 1)
                         [nil (first xs)]
                         [(first xs) (last xs)])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file file opts)
       :function (string/replace function #"Object\." "")
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (string/replace function #"Object\." "")
         :line nil
         :column nil}))))

(comment
  (chrome-st-el->frame
    "\tat cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5356:34)" {})
  )

(defmethod parse-stacktrace :chrome
  [repl-env st err opts]
  (->> st
    string/split-lines
    (drop 1) ;; drop the error message
    (take-while #(not (.startsWith % "\tat eval")))
    (map #(chrome-st-el->frame % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace nil
    "\tat Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4258:8)
\tat Object.cljs$core$first [as first] (http://localhost:9000/out/cljs/core.js:4288:19)
\tat cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5356:34)
\tat http://localhost:9000/out/cljs/core.js:16971:89
\tat cljs.core.map.cljs$core$map__2 (http://localhost:9000/out/cljs/core.js:16972:3)
\tat http://localhost:9000/out/cljs/core.js:10981:129
\tat cljs.core.LazySeq.sval (http://localhost:9000/out/cljs/core.js:10982:3)
\tat cljs.core.LazySeq.cljs$core$ISeqable$_seq$arity$1 (http://localhost:9000/out/cljs/core.js:11073:10)
\tat Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4239:13)
\tat Object.cljs$core$pr_sequential_writer [as pr_sequential_writer] (http://localhost:9000/out/cljs/core.js:28706:14)"
    {:ua-product :chrome}
    nil)

  (parse-stacktrace nil
    "at Object.cljs$core$seq [as seq] (http://localhost:9000/out/cljs/core.js:4259:8)
\tat Object.cljs$core$first [as first] (http://localhost:9000/out/cljs/core.js:4289:19)
\tat cljs$core$ffirst (http://localhost:9000/out/cljs/core.js:5357:18)
\tat eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:1:106)
\tat eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:9:3)
\tat eval (eval at <anonymous> (http://localhost:9000/out/clojure/browser/repl.js:23:272), <anonymous>:14:4)
\tat http://localhost:9000/out/clojure/browser/repl.js:23:267
\tat clojure$browser$repl$evaluate_javascript (http://localhost:9000/out/clojure/browser/repl.js:26:4)
\tat Object.callback (http://localhost:9000/out/clojure/browser/repl.js:121:169)
\tat goog.messaging.AbstractChannel.deliver (http://localhost:9000/out/goog/messaging/abstractchannel.js:142:13)"
    {:ua-product :chrome}
    nil)
  )

;; -----------------------------------------------------------------------------
;; Safari Stacktrace

(defn safari-st-el->frame
  [st-el opts]
  (let [[function flc] (if (re-find #"@" st-el)
                         (string/split st-el #"@")
                         [nil st-el])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file file opts)
       :function function
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (string/trim function)
         :line nil
         :column nil}))))

(comment
  (safari-st-el->frame
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4259:17" {})
  )

(defmethod parse-stacktrace :safari
  [repl-env st err opts]
  (->> st
    string/split-lines
    (take-while #(not (.startsWith % "eval code")))
    (remove string/blank?)
    (map #(safari-st-el->frame % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace nil
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4259:17
cljs$core$first@http://localhost:9000/out/cljs/core.js:4289:22
cljs$core$ffirst@http://localhost:9000/out/cljs/core.js:5357:39
http://localhost:9000/out/cljs/core.js:16972:92
http://localhost:9000/out/cljs/core.js:16973:3
http://localhost:9000/out/cljs/core.js:10982:133
sval@http://localhost:9000/out/cljs/core.js:10983:3
cljs$core$ISeqable$_seq$arity$1@http://localhost:9000/out/cljs/core.js:11074:14
cljs$core$seq@http://localhost:9000/out/cljs/core.js:4240:44
cljs$core$pr_sequential_writer@http://localhost:9000/out/cljs/core.js:28707:17
cljs$core$IPrintWithWriter$_pr_writer$arity$3@http://localhost:9000/out/cljs/core.js:29386:38
cljs$core$pr_writer_impl@http://localhost:9000/out/cljs/core.js:28912:57
cljs$core$pr_writer@http://localhost:9000/out/cljs/core.js:29011:32
cljs$core$pr_seq_writer@http://localhost:9000/out/cljs/core.js:29015:20
cljs$core$pr_sb_with_opts@http://localhost:9000/out/cljs/core.js:29078:24
cljs$core$pr_str_with_opts@http://localhost:9000/out/cljs/core.js:29092:48
cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29130:34
cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29139:39
eval code
eval@[native code]
http://localhost:9000/out/clojure/browser/repl.js:23:271
clojure$browser$repl$evaluate_javascript@http://localhost:9000/out/clojure/browser/repl.js:26:4
http://localhost:9000/out/clojure/browser/repl.js:121:173
deliver@http://localhost:9000/out/goog/messaging/abstractchannel.js:142:21
xpcDeliver@http://localhost:9000/out/goog/net/xpc/crosspagechannel.js:733:19
messageReceived_@http://localhost:9000/out/goog/net/xpc/nativemessagingtransport.js:321:23
fireListener@http://localhost:9000/out/goog/events/events.js:741:25
handleBrowserEvent_@http://localhost:9000/out/goog/events/events.js:862:34
http://localhost:9000/out/goog/events/events.js:276:42"
    {:ua-product :safari}
    nil)
  )

;; -----------------------------------------------------------------------------
;; Firefox Stacktrace

(defn firefox-clean-function [f]
  (as-> f f
    (cond
      (string/blank? f) nil
      (not= (.indexOf f "</") -1)
      (let [idx (.indexOf f "</")]
        (.substring f (+ idx 2)))
      :else f)
    (-> f
      (string/replace #"<" "")
      (string/replace #"\/" ""))))

(defn firefox-st-el->frame
  [st-el opts]
  (let [[function flc] (if (re-find #"@" st-el)
                         (string/split st-el #"@")
                         [nil st-el])
        [file line column] (parse-file-line-column flc)]
    (if (and file function line column)
      {:file (parse-file file opts)
       :function (firefox-clean-function function)
       :line line
       :column column}
      (when-not (string/blank? function)
        {:file nil
         :function (firefox-clean-function function)
         :line nil
         :column nil}))))

(comment
  (firefox-st-el->frame
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4258:8" {})

  (firefox-st-el->frame
    "cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87" {})

  (firefox-st-el->frame
    "cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87" {})

  (firefox-st-el->frame
    "cljs.core.pr_str</cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29138:8" {})

  (firefox-st-el->frame
    "cljs.core.pr_str</cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29129:8" {})
  )

(defmethod parse-stacktrace :firefox
  [repl-env st err opts]
  (->> st
    string/split-lines
    (take-while #(= (.indexOf % "> eval") -1))
    (remove string/blank?)
    (map #(firefox-st-el->frame % opts))
    (remove nil?)
    vec))

(comment
  (parse-stacktrace nil
    "cljs$core$seq@http://localhost:9000/out/cljs/core.js:4258:8
cljs$core$first@http://localhost:9000/out/cljs/core.js:4288:9
cljs$core$ffirst@http://localhost:9000/out/cljs/core.js:5356:24
cljs.core.map</cljs$core$map__2/</<@http://localhost:9000/out/cljs/core.js:16971:87
cljs.core.map</cljs$core$map__2/<@http://localhost:9000/out/cljs/core.js:16970:1
cljs.core.LazySeq.prototype.sval/self__.s<@http://localhost:9000/out/cljs/core.js:10981:119
cljs.core.LazySeq.prototype.sval@http://localhost:9000/out/cljs/core.js:10981:13
cljs.core.LazySeq.prototype.cljs$core$ISeqable$_seq$arity$1@http://localhost:9000/out/cljs/core.js:11073:1
cljs$core$seq@http://localhost:9000/out/cljs/core.js:4239:8
cljs$core$pr_sequential_writer@http://localhost:9000/out/cljs/core.js:28706:4
cljs.core.LazySeq.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3@http://localhost:9000/out/cljs/core.js:29385:8
cljs$core$pr_writer_impl@http://localhost:9000/out/cljs/core.js:28911:8
cljs$core$pr_writer@http://localhost:9000/out/cljs/core.js:29010:8
cljs$core$pr_seq_writer@http://localhost:9000/out/cljs/core.js:29014:1
cljs$core$pr_sb_with_opts@http://localhost:9000/out/cljs/core.js:29077:1
cljs$core$pr_str_with_opts@http://localhost:9000/out/cljs/core.js:29091:23
cljs.core.pr_str</cljs$core$pr_str__delegate@http://localhost:9000/out/cljs/core.js:29129:8
cljs.core.pr_str</cljs$core$pr_str@http://localhost:9000/out/cljs/core.js:29138:8
@http://localhost:9000/out/clojure/browser/repl.js line 23 > eval:1:25
@http://localhost:9000/out/clojure/browser/repl.js line 23 > eval:1:2
clojure$browser$repl$evaluate_javascript/result<@http://localhost:9000/out/clojure/browser/repl.js:23:267
clojure$browser$repl$evaluate_javascript@http://localhost:9000/out/clojure/browser/repl.js:23:15
clojure$browser$repl$connect/</<@http://localhost:9000/out/clojure/browser/repl.js:121:128
goog.messaging.AbstractChannel.prototype.deliver@http://localhost:9000/out/goog/messaging/abstractchannel.js:142:5
goog.net.xpc.CrossPageChannel.prototype.xpcDeliver@http://localhost:9000/out/goog/net/xpc/crosspagechannel.js:733:7
goog.net.xpc.NativeMessagingTransport.messageReceived_@http://localhost:9000/out/goog/net/xpc/nativemessagingtransport.js:321:1
goog.events.fireListener@http://localhost:9000/out/goog/events/events.js:741:10
goog.events.handleBrowserEvent_@http://localhost:9000/out/goog/events/events.js:862:1
goog.events.getProxy/f<@http://localhost:9000/out/goog/events/events.js:276:16"
    {:ua-product :firefox}
    nil)
  )

;; =============================================================================
;; BrowserEnv

(defrecord BrowserEnv []
  repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts))
  repl/IParseStacktrace
  (-parse-stacktrace [this st err opts]
    (parse-stacktrace this st err opts))
  (-evaluate [_ _ _ js] (browser-eval js))
  (-load [this provides url]
    (load-javascript this provides url))
  (-tear-down [_]
    (server/stop)
    (reset! server/state {})
    (reset! browser-state {})))

(defn compile-client-js [opts]
  (cljsc/build
    '[(ns clojure.browser.repl.client
        (:require [goog.events :as event]
                  [clojure.browser.repl :as repl]))
      (defn start [url]
        (event/listen js/window
          "load"
          (fn []
            (repl/start-evaluator url))))]
    {:optimizations (:optimizations opts)
     :output-dir (:working-dir opts)}))

(defn create-client-js-file [opts file-path]
  (let [file (io/file file-path)]
    (when (not (.exists file))
      (spit file (compile-client-js opts)))
    file))

(defn- provides-and-requires
  "Return a flat list of all provided and required namespaces from a
  sequence of IJavaScripts."
  [deps]
  (flatten (mapcat (juxt :provides :requires) deps)))

;; TODO: the following is questionable as it triggers compilation
;; this code should other means to determine the dependencies for a
;; namespace - David

(defn- always-preload
  "Return a list of all namespaces which are always loaded into the browser
  when using a browser-connected REPL.

  Uses the working-dir (see repl-env) to output intermediate compilation."
  [& [{:keys [working-dir]}]]
  (let [opts (if working-dir {:output-dir working-dir}
                 {})
        cljs (provides-and-requires
              (cljsc/cljs-dependencies opts ["clojure.browser.repl"]))
        goog (provides-and-requires
               (cljsc/js-dependencies opts cljs))]
    (disj (set (concat cljs goog)) nil)))

;; NOTE: REPL evaluation environment designers do not replicate the behavior
;; of the browser REPL. The design is outdated, refer to the Node.js, Rhino or
;; Nashorn REPLs.

(defn repl-env* [opts]
  (let [ups-deps (cljsc/get-upstream-deps)
        opts (assoc opts
               :ups-libs (:libs ups-deps)
               :ups-foreign-libs (:foreign-libs ups-deps))
        compiler-env (cljs.env/default-compiler-env opts)
        opts (merge (BrowserEnv.)
               {:port           9000
                :optimizations  :simple
                :working-dir    (or (:output-dir opts)
                                  (->> [".repl" (util/clojurescript-version)]
                                    (remove empty?) (string/join "-")))
                :serve-static   true
                :static-dir     (cond-> ["." "out/"]
                                  (:output-dir opts) (conj (:output-dir opts)))
                :preloaded-libs []
                :src            "src/"
                ::env/compiler  compiler-env
                :source-map     false}
               opts)]
    (cljs.env/with-compiler-env compiler-env
      (reset! preloaded-libs
        (set (concat
               (always-preload opts)
               (map str (:preloaded-libs opts)))))
      (reset! loaded-libs @preloaded-libs)
      (println "Compiling client js ...")
      (swap! browser-state
        (fn [old]
          (assoc old :client-js
                     (create-client-js-file
                       opts
                       (io/file (:working-dir opts) "client.js")))))
      (println "Waiting for browser to connect ...")
      opts)))

(defn repl-env
  "Create a browser-connected REPL environment.

  Options:

  port:           The port on which the REPL server will run. Defaults to 9000.
  working-dir:    The directory where the compiled REPL client JavaScript will
                  be stored. Defaults to \".repl\" with a ClojureScript version
                  suffix, eg. \".repl-0.0-2138\".
  serve-static:   Should the REPL server attempt to serve static content?
                  Defaults to true.
  static-dir:     List of directories to search for static content. Defaults to
                  [\".\" \"out/\"].
  preloaded-libs: List of namespaces that should not be sent from the REPL server
                  to the browser. This may be required if the browser is already
                  loading code and reloading it would cause a problem.
  optimizations:  The level of optimization to use when compiling the client
                  end of the REPL. Defaults to :simple.
  src:            The source directory containing user-defined cljs files. Used to
                  support reflection. Defaults to \"src/\".
  "
  [& {:as opts}]
  (assert (even? (count opts)) "Arguments must be interleaved key value pairs")
  (repl-env* opts))

(comment

  (require '[cljs.repl :as repl])
  (require '[cljs.repl.browser :as browser])
  (def env (browser/repl-env))
  (repl/repl env)
  ;; simulate the browser with curl
  ;; curl -v -d "ready" http://127.0.0.1:9000
  ClojureScript:> (+ 1 1)
  ;; curl -v -d "2" http://127.0.0.1:9000

  )
