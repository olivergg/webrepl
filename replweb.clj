#!/usr/bin/env bb
;; A web console for a running JVM's in-process nREPL.
;;
;;     ./replweb.clj [--port 7899] [--nrepl 127.0.0.1:5555] [--notebook PATH] [--config PATH]
;;
;; Routes:
;;   GET  /                     the page (replweb.html, re-read per request)
;;   GET  /stream?c=<id>        Server-Sent Events channel for one browser tab
;;   POST /eval?c=<id>          body = Clojure code, replies streamed over that channel
;;   POST /stop?c=<id>          interrupt the running eval
;;   GET  /complete?c=&prefix=  symbol completion   (nREPL `completions` op)
;;   GET  /members?c=&sym=&prefix=  Java method names on the class of `sym`'s value (reflection)
;;   GET  /lookup?c=&sym=       arglists + docstring (nREPL `lookup` op)
;;   GET  /snippets             the notebook's (comment ...) blocks, by theme
;;   POST /lint                 body = code, reports whether it reads cleanly (never evals it)
;;   GET  /config               title, home-ns and quick-access shortcuts (config.edn)
;;
;; Completion and docs come from nREPL core's own nrepl.util.completion /
;; nrepl.util.lookup (built in since 1.2) - no cider-nrepl needed in the app.
;;
;; Each tab gets its own nREPL session, dropped into the configured home namespace.
;; Bind is localhost-only and cross-site requests are refused (see `same-origin?`): this is
;; arbitrary eval against a live JVM.
;;
;; Project-specific bits (title, quick-access shortcuts, starter snippets, notebook path,
;; home namespace) live in config.edn - see config.example.edn - so this file and
;; replweb.html stay usable against any nREPL, not just one particular app.

(require '[bencode.core :as bencode]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[org.httpkit.server :as hk])

(import '[java.io PushbackInputStream BufferedOutputStream]
        '[java.net Socket URLDecoder]
        '[java.util UUID])

;; ─────────────────────────────────────────────────────────────────────────────
;; nREPL client — one socket, replies routed to per-message handlers
;; ─────────────────────────────────────────────────────────────────────────────

;; [title pattern] pairs, first match wins - matched against a snippet's title+code (see
;; `theme-of`). Patterns are plain strings, not regex literals: `clojure.edn` (which reads
;; config.edn) has no `#"..."` syntax, so both this default and any :themes override in
;; config.edn share the same shape, compiled to a regex in -main.
(def default-themes
  [["Database"       "(?i)\\bdao\\b|\\bsql\\b|transaction|hibernate|n\\+1|lazy.?load"]
   ["HTTP & APIs"    "(?i)\\bhttp\\b|endpoint|\\brest\\b|webhook|\\bapi\\b"]
   ["Messaging"      "(?i)kafka|queue|\\btopic\\b|producer|consumer|pubsub"]
   ["Jobs & batch"   "(?i)\\bjob\\b|batch|scheduler|\\bcron\\b|oneshot|one-shot"]
   ["Cache"          "(?i)\\bcache\\b|evict|redis|hazelcast"]
   ["Config & flags" "(?i)feature.?flag|config|@value|env(ironment)?"]
   ["Exports & docs" "(?i)export|\\bpdf\\b|\\bcsv\\b|xlsx|document"]])

(def default-config
  {:title "Clojure nREPL console"
   :home-ns "user"
   :quick-access []
   :quick-access-theme "quick access"
   :starters []
   :themes default-themes})

(defn- load-config [path]
  (merge default-config
         (when (and path (.exists (io/file path)))
           (edn/read-string (slurp path)))))

;; Identifies this bridge process. It rides along on `ready` so a browser restoring a
;; persisted transcript can tell whether its stored result ids still mean anything: result-n
;; restarts at 0 with the process, while the app-side store outlives it, so a stale id would
;; otherwise resolve to a different value entirely. Mutable: also bumped on every nREPL
;; reconnect (connect-with-retry!), which invalidates ids the same way a process restart does.
(defonce ^:private boot-id (atom (str (UUID/randomUUID))))

(defonce conn (atom nil))          ; {:out ..., :lock ...}
(defonce handlers (atom {}))       ; message id -> (fn [msg])

(defn- bytes->str
  "bencode decodes byte strings as byte[]; walk them back to String."
  [x]
  (cond
    (bytes? x)  (String. ^bytes x "UTF-8")
    (map? x)    (update-vals x bytes->str)
    (vector? x) (mapv bytes->str x)
    :else       x))

(defn- new-id [] (str (UUID/randomUUID)))
(defn- done? [msg] (contains? (set (:status msg)) "done"))

(defn- send! [msg]
  (let [{:keys [out lock]} @conn]
    (locking lock
      (bencode/write-bencode out msg)
      (.flush out))))

(defn request!
  "Sends `msg` (must carry its own \"id\"), routing every reply to `on-msg` until done."
  [msg on-msg]
  (let [id (get msg "id")]
    (swap! handlers assoc id (fn [m]
                               (on-msg m)
                               (when (done? m) (swap! handlers dissoc id))))
    (send! msg)))

(defn blocking!
  "Sends `msg` (no id needed) and returns every reply up to `done`."
  [msg]
  (let [acc (atom [])
        p   (promise)]
    (request! (assoc msg "id" (new-id))
              (fn [m]
                (swap! acc conj m)
                (when (done? m) (deliver p @acc))))
    (deref p 10000 [])))

(defn new-session! []
  (let [p (promise)]
    (request! {"op" "clone" "id" (new-id)}
              (fn [m] (when-let [s (:new-session m)] (deliver p s))))
    (deref p 5000 nil)))

(defn eval-value
  "Blocking-evals `code` in `session` and reads its first :value back as data, or nil if
   there wasn't one or it didn't read as EDN - the app produces this, but it crosses a
   socket, hence edn/read-string rather than read-string."
  [session code]
  (->> (blocking! {"op" "eval" "code" code "session" session})
       (keep :value)
       first
       (#(try (edn/read-string %) (catch Exception _ nil)))))

(defn connect!
  "Opens the nREPL socket and starts its reader thread. `on-lost`, if given, fires once the
   reader loop exits (socket closed or errored) so a caller can reconnect."
  ([host port] (connect! host port nil))
  ([host port on-lost]
   (let [sock (Socket. ^String host (int port))
         out  (BufferedOutputStream. (.getOutputStream sock))
         in   (PushbackInputStream. (.getInputStream sock))]
     (reset! conn {:out out :lock (Object.)})
     (doto (Thread.
            (fn []
              (try
                (loop []
                  (when-let [raw (bencode/read-bencode in)]
                    (let [m (-> raw bytes->str walk/keywordize-keys)]
                      (when-let [h (get @handlers (:id m))] (h m)))
                    (recur)))
                (catch Exception e
                  (println "[replweb] nrepl connection lost:" (.getMessage e))
                  (when on-lost (on-lost)))))
            "nrepl-reader")
       (.setDaemon true)
       (.start)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Browser tabs
;; ─────────────────────────────────────────────────────────────────────────────

(defonce tabs (atom {}))   ; tab id -> {:ch .. :session .. :running .. :ns ..}

(defn- sse! [ch event data]
  (hk/send! ch (str "event: " event "\ndata: " (json/generate-string data) "\n\n") false))

(defn- emit! [tab-id event data]
  (when-let [ch (get-in @tabs [tab-id :ch])] (sse! ch event data)))

(defn- broadcast! [event data]
  (doseq [{:keys [ch]} (vals @tabs)] (sse! ch event data)))

;; ─────────────────────────────────────────────────────────────────────────────
;; tap> inspector
;; ─────────────────────────────────────────────────────────────────────────────

;; Installed once in the app JVM. Tapped values are KEPT THERE, keyed by index; the browser
;; only ever receives a shallow description of the node it is looking at, and walks deeper by
;; asking for a path. That is what makes (tap> (range)) or a Hibernate entity graph safe to
;; inspect - nothing large is ever serialized, and nothing is realized beyond `lim`.
;; Paths are vectors of child *indices*, so a map key can be any value without having to
;; survive a round-trip through the wire.
(def inspector-setup
  (pr-str
   '(do
      (def replweb-lim 200)
      (def replweb-taps (atom {}))     ; idx -> the tapped value itself, capped
      (def replweb-tap-n (atom 0))

      (defn replweb-kind [v]
        (cond (nil? v)                          "nil"
              (map? v)                          "map"
              (vector? v)                       "vector"
              (set? v)                          "set"
              (string? v)                       "string"
              (keyword? v)                      "keyword"
              (number? v)                       "number"
              (instance? Boolean v)             "bool"
              (seq? v)                          "seq"
              (.isArray (class v))              "array"
              (instance? java.util.Map v)       "jmap"
              (instance? java.util.Collection v) "jcoll"
              (instance? java.util.Date v)      "scalar"
              (instance? Class v)               "scalar"
              :else                             "object"))

      (def replweb-branch-kinds
        #{"map" "vector" "set" "seq" "array" "jmap" "jcoll" "object"})

      (defn replweb-children
        "[{:i idx :label str :v value}] - bounded, never realizes more than replweb-lim."
        [v]
        (try
          (case (replweb-kind v)
            ("map" "jmap")
            (map-indexed (fn [i e] {:i i :label (pr-str (key e)) :v (val e)})
                         (take replweb-lim (seq v)))

            ("vector" "set" "seq" "jcoll" "array")
            (map-indexed (fn [i x] {:i i :label (str i) :v x})
                         (take replweb-lim (seq v)))

            "object"
            (->> (.getDeclaredFields (class v))
                 (remove #(java.lang.reflect.Modifier/isStatic (.getModifiers %)))
                 (take replweb-lim)
                 (map-indexed (fn [i f]
                                {:i i :label (.getName f)
                                 :v (try (.setAccessible f true) (.get f v)
                                         (catch Throwable t (str "<inaccessible: "
                                                                 (.getSimpleName (class t)) ">")))})))
            [])
          (catch Throwable t [{:i 0 :label "!" :v (str "<" (.getMessage t) ">")}])))

      (defn replweb-preview [v]
        (try
          (let [s (binding [*print-length* 12 *print-level* 3] (pr-str v))]
            (if (> (count s) 200) (str (subs s 0 200) "…") s))
          (catch Throwable t (str "<unprintable: " (.getSimpleName (class t)) ">"))))

      (defn replweb-desc [label v]
        {:label  label
         :kind   (replweb-kind v)
         :preview (replweb-preview v)
         ;; branch? by kind, never by counting children: a lazy seq must not be realized
         ;; just to decide whether to draw a disclosure triangle.
         :branch (boolean (replweb-branch-kinds (replweb-kind v)))})

      (defn replweb-node
        "Shallow description of the node at `path` (a vector of child indices) under tap `idx`."
        [idx path]
        (let [root (get @replweb-taps idx)
              v    (reduce (fn [acc i] (:v (nth (vec (replweb-children acc)) i nil))) root path)
              kids (vec (replweb-children v))]
          (assoc (replweb-desc nil v)
                 :class (when (some? v) (.getName (class v)))
                 :n (count kids)
                 :children (mapv (fn [{:keys [i label v]}]
                                   (assoc (replweb-desc label v) :i i))
                                 kids))))

      ;; Result store, so "inspect" on a transcript row can name the exact value that row
      ;; produced instead of trusting *1 *2 *3 - which shift under it on every later eval,
      ;; and which the tap it used to send would itself have shifted.
      (def replweb-results (atom {}))   ; result id -> the value, last 100 kept

      (defn replweb-keep [rid v]
        (swap! replweb-results (fn [m] (-> m (assoc rid v) (dissoc (- rid 100)))))
        nil)

      (defn replweb-tap-result [rid]
        (when-let [e (find @replweb-results rid)] (tap> (val e)) true))

      ;; The defs above land in whatever namespace this session happens to be in; tab
      ;; sessions live somewhere else entirely, so hand that name back to the bridge and
      ;; let it qualify the calls it makes.
      (str *ns*))))

;; Drains tap> values, emitting one EDN description per value, each terminated by a NUL
;; marker so the bridge can reassemble them out of arbitrarily chunked `out`.
;;  - bounded queue + .offer: a tap storm drops values instead of growing the heap
;;  - fresh queue per run + remove-tap of the previous fn, and the loop exits once it is no
;;    longer the current pump: restarting replweb against a still-running app would
;;    otherwise leave the old loop alive, competing for the same queue.
(def tap-pump
  (pr-str
   '(do
      (when-let [old (resolve 'replweb-tap-fn)] (remove-tap @old))
      (def replweb-tap-q (java.util.concurrent.LinkedBlockingQueue. 256))
      (def replweb-tap-fn (let [q replweb-tap-q] (fn [v] (.offer q v))))
      (add-tap replweb-tap-fn)
      (let [q    replweb-tap-q
            ;; built from (char 0) rather than written literally, so neither this file nor
            ;; the code sent over the wire carries a raw NUL byte
            mark (str (char 0) "END" (char 0))]
        (while (identical? q replweb-tap-q)
          (when-let [v (.poll q 2 java.util.concurrent.TimeUnit/SECONDS)]
            (let [idx (swap! replweb-tap-n inc)]
              ;; hold the value for later drill-down, keeping only the last 100
              (swap! replweb-taps (fn [m] (-> m (assoc idx v) (dissoc (- idx 100)))))
              (print (pr-str (assoc (replweb-desc nil v) :i idx))))
            (print mark)
            (flush)))))))

(def ^:private tap-mark (str (char 0) "END" (char 0)))
(defonce ^:private tap-buf (atom ""))

(defn- split-taps
  "Splits `s` into [complete-values trailing-partial]."
  [s]
  (loop [s s, out []]
    (if-let [i (str/index-of s tap-mark)]
      (recur (subs s (+ i (count tap-mark))) (conj out (subs s 0 i)))
      [out s])))

;; Only ever called from the nREPL reader thread, so a plain read-modify-write is fine.
(defn- on-tap-chunk [chunk]
  (let [[values remainder] (split-taps (str @tap-buf chunk))]
    (reset! tap-buf remainder)
    (doseq [v values]
      ;; edn/read-string, never read-string: the app produces this, but it crosses a socket.
      (when-let [desc (try (edn/read-string v) (catch Exception _ nil))]
        (broadcast! "tap" (assoc desc :at (System/currentTimeMillis)))))))

(defonce ^:private inspect-session (atom nil))
;; Namespace the inspector's vars ended up in, reported back by inspector-setup. Every call
;; the bridge makes into them is qualified with it, since tab sessions sit in home-ns.
(defonce ^:private inspect-ns (atom "user"))

(defn- iq
  "Qualifies an inspector var with the namespace it was actually defined in."
  [sym] (str @inspect-ns "/" sym))

(defn start-tap-pump! []
  (if-let [session (new-session!)]
    (do
      ;; The inspector's vars must exist before the pump's loop starts calling replweb-desc.
      (let [ns (eval-value session inspector-setup)]
        (when (string? ns) (reset! inspect-ns ns)))
      (reset! inspect-session (new-session!))
      ;; Never completes: the loop blocks on .poll, and each tapped value arrives as `out`.
      (request! {"op" "eval" "code" tap-pump "session" session "id" (new-id)}
                (fn [m]
                  (when-let [o (:out m)] (on-tap-chunk o))
                  (when-let [e (:err m)] (println "[replweb] tap pump:" e)))))
    (println "[replweb] tap pump unavailable: could not open a session")))

(defn inspect-node
  "Shallow description of the node at `path` under tapped value `idx`, read back as data."
  [idx path]
  (when-let [session @inspect-session]
    (eval-value session (format "(%s %d %s)" (iq "replweb-node") idx (pr-str (vec path))))))

(defn tap-result!
  "Pushes the value a transcript row produced into the inspector, from the inspect session so
   the user's own *1 *2 *3 are left alone."
  [rid]
  (when-let [session @inspect-session]
    (->> (blocking! {"op" "eval" "code" (format "(%s %d)" (iq "replweb-tap-result") rid)
                     "session" session})
         (keep :value)
         first
         (= "true"))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Snippet linting — read, never eval, so unbalanced parens or a bad literal show up on a
;; notebook snippet before you ever run it
;; ─────────────────────────────────────────────────────────────────────────────

(defonce ^:private lint-session (atom nil))

(defn- ensure-lint-session! []
  (or @lint-session (when-let [s (new-session!)] (reset! lint-session s))))

(defn lint-code
  "Reads `code` (every top-level form) through the target JVM's own reader with *read-eval*
   off, so nothing in it ever runs - only its own dedicated session touches this, same as the
   inspector's, so it can't race a tab's eval or its *1 *2 *3."
  [code]
  (if-let [session (ensure-lint-session!)]
    (let [wrapped (format "(binding [*read-eval* false]
                             (let [rdr (clojure.lang.LineNumberingPushbackReader.
                                         (java.io.StringReader. %s))]
                               (loop [] (let [f (read rdr false :eof)]
                                          (when-not (= f :eof) (recur))))))"
                           (pr-str code))
          msgs (blocking! {"op" "eval" "code" wrapped "session" session})
          err  (apply str (keep :err msgs))]
      (if (some :ex msgs)
        {:ok false :error (if (seq err) (first (str/split-lines err)) "syntax error")}
        {:ok true}))
    {:ok nil}))

;; ─────────────────────────────────────────────────────────────────────────────
;; nREPL reconnect — the socket dying doesn't take the HTTP server down with it, so this is
;; the only thing standing between "target JVM restarted" and a bridge stuck forever pointing
;; at a dead socket.
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private reconnect-base-ms 1000)
(def ^:private reconnect-max-ms 30000)

(defn- on-reconnected!
  "Every session made against the old socket (lint, inspector, each tab's) is gone with it,
   and so is anything the app JVM held for them. Bump the boot id - browsers already know how
   to treat that as a hard restart, same path as the bridge process itself restarting - and
   force every open SSE channel closed so each tab's next auto-reconnect re-derives a fresh
   session via h-stream's :on-open."
  []
  (reset! lint-session nil)
  (reset! inspect-session nil)
  (reset! boot-id (str (UUID/randomUUID)))
  (doseq [{:keys [ch]} (vals @tabs)] (hk/close ch))
  (reset! tabs {})
  (start-tap-pump!))

(defn connect-with-retry!
  "connect!, but on failure or a later drop, keeps trying with exponential backoff instead of
   leaving the bridge stuck on a dead socket. Covers both the target JVM not being up yet at
   startup and it restarting later."
  ([host port] (connect-with-retry! host port reconnect-base-ms))
  ([host port wait-ms]
   (try
     (connect! host port #(connect-with-retry! host port reconnect-base-ms))
     (on-reconnected!)
     (catch Exception e
       (println (format "[replweb] nrepl connect failed (%s) - retrying in %dms"
                         (.getMessage e) wait-ms))
       (Thread/sleep ^long wait-ms)
       (connect-with-retry! host port (min reconnect-max-ms (* wait-ms 2)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Snippet library — the (comment ...) blocks of a Clojure notebook, by theme
;; ─────────────────────────────────────────────────────────────────────────────

;; First match wins, so a notebook's own :themes (see default-themes above) should run
;; narrowest-first: broad catch-alls have to come last or they swallow everything. Matched
;; against title+code, which keeps this working as the notebook grows - no per-snippet
;; bookkeeping, and a new (comment ...) block is classified the moment it is saved.
(defn- theme-of [themes text]
  (or (some (fn [[title re]] (when (re-find re text) title)) themes) "Misc"))

(defn- block-end
  "Index just past the paren that closes the form starting at `i`, skipping over strings,
   character literals and line comments (all of which can hold unbalanced parens)."
  [^String s i]
  (loop [i i, depth 0, in-str? false, esc? false]
    (if (>= i (count s))
      i
      (let [c (.charAt s i)]
        (cond
          esc?       (recur (inc i) depth in-str? false)
          in-str?    (case c
                       \\ (recur (inc i) depth true true)
                       \" (recur (inc i) depth false false)
                       (recur (inc i) depth true false))
          (= c \\)   (recur (+ i 2) depth false false)
          (= c \")   (recur (inc i) depth true false)
          (= c \;)   (recur (or (str/index-of s "\n" i) (count s)) depth false false)
          (= c \()   (recur (inc i) (inc depth) false false)
          (= c \))   (if (= depth 1) (inc i) (recur (inc i) (dec depth) false false))
          :else      (recur (inc i) depth false false))))))

(defn parse-notebook
  "Each top-level (comment ...) form becomes one snippet: its first ;;-line is the title,
   the rest is ready-to-paste code (the wrapper and the title line stripped)."
  [path themes]
  (if-not (and path (.exists (io/file path)))
    []
    (let [src (slurp path)]
      (->> (loop [i 0, out []]
             (if-let [j (str/index-of src "\n(comment" i)]
               (let [start (inc j), end (block-end src start)]
                 (recur end (conj out (subs src start end))))
               out))
           (keep (fn [block]
                   (let [lines  (str/split-lines block)
                         body   (rest lines)                       ; drop "(comment"
                         title? #(str/starts-with? (str/trim %) ";")
                         title  (some->> body (filter title?) first str/trim
                                         (re-find #"^;+\s*(.*)$") second str/trim)
                         code   (->> body
                                     (remove #(= (str/trim %) ""))
                                     (drop-while title?)           ; leading description
                                     (str/join "\n")
                                     ;; the block's trailing ")" closes (comment - drop it
                                     (#(str/replace % #"\)\s*$" ""))
                                     str/trim)]
                     (when (and (seq code) (seq title) (not= title ""))
                       {:title title
                        :code  (str/replace code #"(?m)^  " "")     ; unindent one level
                        :theme (theme-of themes (str title " " code))}))))
           (sort-by (juxt :theme :title))
           vec))))

;; ─────────────────────────────────────────────────────────────────────────────
;; HTTP
;; ─────────────────────────────────────────────────────────────────────────────

(defn- params [req]
  (->> (str/split (or (:query-string req) "") #"&")
       (keep #(let [[k v] (str/split % #"=" 2)]
                (when (seq k) [k (URLDecoder/decode (or v "") "UTF-8")])))
       (into {})))

(defn- json-res [body]
  {:status 200 :headers {"Content-Type" "application/json; charset=utf-8"
                         "X-Content-Type-Options" "nosniff"}
   :body (json/generate-string body)})

(defn- tab-of [req]
  (let [id (get (params req) "c")]
    (when-let [t (get @tabs id)] (when (:session t) (assoc t :id id)))))

(defn- h-stream [req nrepl-endpoint home-ns]
  (let [id (get (params req) "c")]
    (hk/as-channel req
      {:on-open
       (fn [ch]
         (hk/send! ch {:status 200
                       :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                 "Cache-Control" "no-cache"
                                 "X-Accel-Buffering" "no"}}
                   false)
         (swap! tabs assoc id {:ch ch :ns home-ns})
         (if-let [session (new-session!)]
           (do
             (swap! tabs assoc-in [id :session] session)
             (request! {"op" "eval" "code" (str "(in-ns '" home-ns ")")
                        "session" session "id" (new-id)} (fn [_]))
             (request! {"op" "eval"
                        "code" (str "[(str (java.net.InetAddress/getLocalHost))"
                                    " (System/getProperty \"env.profile\")]")
                        "session" session "id" (new-id)}
                       (fn [m]
                         (when-let [v (:value m)]
                           (emit! id "ready" {:ns home-ns :identity v
                                              :nrepl nrepl-endpoint
                                              :boot @boot-id})))))
           (emit! id "ready" {:ns "?" :error "could not open an nREPL session"})))
       :on-close (fn [_ _]
                   (when-let [s (get-in @tabs [id :session])]
                     (request! {"op" "close" "session" s "id" (new-id)} (fn [_])))
                   (swap! tabs dissoc id))})))

(defonce ^:private result-n (atom 0))

(defn- keep-result!
  "Hands the value the row just produced to the app's result store, under `rid`.

   The form ends in *1 so *1 keeps the exact value the user is looking at; only *2 and *3
   take a duplicate. Without this the inspect button had to send (tap> *N) itself, and that
   eval shifted the very history it was counting on - the reason it worked every other click."
  [session rid]
  (request! {"op" "eval" "code" (format "(do (%s %d *1) *1)" (iq "replweb-keep") rid)
             "session" session "id" (new-id)}
            (fn [_])))

(defn- h-eval [req]
  (if-let [{:keys [id session running]} (tab-of req)]
    (if running
      {:status 429 :body ""}
      (let [code    (slurp (:body req))
            eval-id (new-id)
            rid     (swap! result-n inc)
            t0      (System/nanoTime)
            msg     (cond-> {"op" "eval" "code" code "session" session "id" eval-id}
                      (= "1" (get (params req) "pprint"))
                      ;; nrepl.util.print/pprint, not clojure.pprint/pprint: only the former
                      ;; has the [value writer options] arity wrap-print calls, so only it
                      ;; honours :right-margin.
                      (assoc "nrepl.middleware.print/print" "nrepl.util.print/pprint"
                             "nrepl.middleware.print/options" {"right-margin" 92}))]
        (swap! tabs assoc-in [id :running] eval-id)
        (request! msg
                  (fn [m]
                    (when-let [ns' (:ns m)] (swap! tabs assoc-in [id :ns] ns'))
                    (when-let [v (:out m)]   (emit! id "msg" {:t "out" :v v}))
                    (when-let [v (:err m)]   (emit! id "msg" {:t "err" :v v}))
                    (when-let [v (:value m)] (emit! id "msg" {:t "value" :v v :ns (:ns m)}))
                    (when (:ex m)            (emit! id "msg" {:t "ex" :v (:ex m)}))
                    (when (done? m)
                      (swap! tabs assoc-in [id :running] nil)
                      (keep-result! session rid)
                      (emit! id "msg" {:t "done"
                                       :rid rid
                                       :ms (quot (- (System/nanoTime) t0) 1000000)
                                       :ns (get-in @tabs [id :ns])
                                       :interrupted (boolean
                                                     (some #{"interrupted"} (:status m)))}))))
        {:status 202 :body ""}))
    {:status 409 :body ""}))

(defn- h-stop [req]
  (if-let [{:keys [session running]} (tab-of req)]
    (do (when running
          (request! {"op" "interrupt" "session" session
                     "interrupt-id" running "id" (new-id)} (fn [_])))
        {:status 202 :body ""})
    {:status 404 :body ""}))

(defn- h-complete [req]
  (let [{:keys [session ns]} (tab-of req)
        prefix (get (params req) "prefix")]
    (json-res
     (if (and session (seq prefix))
       (->> (blocking! {"op" "completions" "prefix" prefix "ns" ns "session" session})
            (mapcat :completions)
            vec)
       []))))

(defn- h-members
  "GET /members?c=&sym=&prefix= — public method names on the class of `sym`'s current
   value, via reflection on the target JVM - no cider-nrepl needed. `sym` must already be
   bound (typically a (def ...)'d service): completing (-> s .foo) needs `s` bound first,
   since there's no static type info to fall back on here."
  [req]
  (let [{:keys [session]} (tab-of req)
        sym    (get (params req) "sym")
        prefix (or (get (params req) "prefix") "")]
    (json-res
     ;; `sym` is spliced into code: accept a bare symbol only, never a form
     (if (and session sym (re-matches #"[\w.*+!?<>=/$-]+" sym))
       (->> (eval-value session
                        (format "(->> (.getMethods (class %s)) (map (fn [m] (.getName m))) distinct sort vec)"
                                sym))
            (filter #(str/starts-with? % prefix))
            vec)
       []))))

(defn- h-lookup [req]
  (let [{:keys [session ns]} (tab-of req)
        sym (get (params req) "sym")]
    (json-res
     (when (and session (seq sym))
       (->> (blocking! {"op" "lookup" "sym" sym "ns" ns "session" session})
            (keep :info)
            (remove empty?)
            first)))))

(defn- h-inspect
  "GET /inspect?i=<tap index>&path=1.0.3 — one level of the tapped value's tree."
  [req]
  (let [p    (params req)
        idx  (parse-long (or (get p "i") ""))
        path (->> (str/split (or (get p "path") "") #"\.")
                  (keep parse-long)
                  vec)]
    (json-res (when idx (inspect-node idx path)))))

(defn- h-inspect-result
  "POST /inspect-result?rid=<result id> — sends that row's value to the inspector."
  [req]
  (if-let [rid (parse-long (or (get (params req) "rid") ""))]
    (json-res {:ok (boolean (tap-result! rid))})
    {:status 400 :body ""}))

(defn- h-lint
  "POST /lint — body is a snippet's code; reports whether it reads cleanly, never evals it."
  [req]
  (json-res (lint-code (slurp (:body req)))))

(defn- h-page [html-path nrepl-endpoint]
  (let [html (if (.exists (io/file html-path))
               (slurp html-path)
               (slurp (io/resource "replweb.html")))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"
               "X-Content-Type-Options" "nosniff"
               ;; Everything is same-origin (relative fetches, inline style/script): 'self'
               ;; blocks any external request, even one a future bug introduces.
               "Content-Security-Policy"
               (str "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                    "style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self'; "
                    "font-src 'self'; frame-ancestors 'none'; form-action 'self'")}
     :body (str/replace html "__NREPL__" nrepl-endpoint)}))

(defn- same-origin?
  "Localhost binding alone doesn't stop other sites: the user's own browser can still reach
   us via CSRF (cross-site fetch/iframe) or DNS rebinding. Host defeats rebinding; Origin and
   Sec-Fetch-Site (absent on non-browser clients like curl) defeat cross-site requests."
  [{:keys [headers]} port]
  (let [{:strs [host origin sec-fetch-site]} headers]
    (and (#{(str "127.0.0.1:" port) (str "localhost:" port)} host)
         (or (nil? origin) (= origin (str "http://" host)))
         (contains? #{nil "same-origin" "none"} sec-fetch-site))))

(defn- router [{:keys [html nrepl-endpoint snippets config port]}]
  (let [home-ns (:home-ns config)]
    (fn [req]
      (if-not (same-origin? req port)
        {:status 403 :body ""}
        (case (:uri req)
          "/"            (h-page html nrepl-endpoint)
          "/stream"      (h-stream req nrepl-endpoint home-ns)
          "/eval"        (h-eval req)
          "/stop"        (h-stop req)
          "/complete"    (h-complete req)
          "/members"     (h-members req)
          "/lookup"      (h-lookup req)
          "/inspect"     (h-inspect req)
          "/inspect-result" (h-inspect-result req)
          "/lint"        (h-lint req)
          "/snippets"    (json-res (snippets))
          "/config"      (json-res {:title (:title config)
                                     :quickAccess (:quick-access config)
                                     :quickAccessTheme (:quick-access-theme config)
                                     :starters (:starters config)})
          "/favicon.ico" {:status 204 :body ""}
          {:status 404 :body ""})))))

;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private usage
  "Usage: replweb.clj [options]

Options:
  --nrepl HOST:PORT   nREPL socket to connect to (default 127.0.0.1:5555)
  --port PORT         HTTP port to serve the console on (default 7899)
  --notebook PATH     Clojure file whose (comment ...) forms become snippets;
                       overrides :notebook from config.edn
  --config PATH       config.edn to load (default ./config.edn), see config.example.edn
  -h, --help          show this help and exit")

(defn -main [& args]
  (when (some #{"-h" "--help"} args)
    (println usage)
    (System/exit 0))
  (let [opts (loop [[a v & more :as all] args, m {}]
               (if (empty? all)
                 m
                 (recur more (case a
                               "--port"     (assoc m :port (parse-long v))
                               "--nrepl"    (let [[h p] (str/split v #":" 2)]
                                              (assoc m :nrepl-host h :nrepl-port (parse-long p)))
                               "--notebook" (assoc m :notebook v)
                               "--config"   (assoc m :config-path v)
                               (do (println "unknown arg:" a)
                                   (println usage)
                                   (System/exit 2))))))
        {:keys [port nrepl-host nrepl-port notebook config-path]
         :or   {port 7899 nrepl-host "127.0.0.1" nrepl-port 5555
                config-path "config.edn"}} opts
        config   (load-config config-path)
        notebook (or notebook (:notebook config))
        themes   (mapv (fn [[title pattern]] [title (re-pattern pattern)]) (:themes config))
        endpoint (str nrepl-host ":" nrepl-port)
        ;; A thunk, not a cached value: editing the notebook shows up on a browser reload.
        snippets #(parse-notebook notebook themes)]
    ;; Backgrounded: retrying (with the target JVM possibly not up yet) must not hold up the
    ;; HTTP server - tabs already cope with "no session yet" until this succeeds.
    (future (connect-with-retry! nrepl-host nrepl-port))
    (hk/run-server (router {:html "replweb.html" :nrepl-endpoint endpoint
                             :snippets snippets :config config :port port})
                   {:port port :ip "127.0.0.1"})
    (println (format "[replweb] http://localhost:%d  ->  nrepl %s" port endpoint))
    (println (if notebook
               (format "[replweb] %d snippets from %s" (count (snippets)) notebook)
               "[replweb] no notebook configured - see --notebook or :notebook in config.edn"))
    @(promise)))

(apply -main *command-line-args*)
