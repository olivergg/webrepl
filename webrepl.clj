#!/usr/bin/env bb
;; A web console for a running JVM's in-process nREPL.
;;
;;     ./webrepl.clj [--port 7899] [--nrepl 127.0.0.1:5555] [--notebook PATH] [--config PATH]
;;
;; Routes:
;;   GET  /                     the page (webrepl.html, re-read per request)
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
;; webrepl.html stay usable against any nREPL, not just one particular app.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[bencode.core :as bencode]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[org.httpkit.server :as hk])

(import '[java.io PushbackInputStream BufferedOutputStream]
        '[java.net Socket URLDecoder]
        '[java.security MessageDigest SecureRandom]
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

;; What may be spliced into eval'd code as a name: a bare symbol, never a form.
(def ^:private bare-symbol #"[\w.*+!?<>=/$-]+")

(defn- random-hex [bits]
  (format (str "%0" (quot bits 4) "x") (BigInteger. (int bits) (SecureRandom.))))

(defn- load-config [path]
  (let [config (merge default-config
                      (when (and path (fs/exists? path))
                        (edn/read-string (slurp path))))]
    ;; spliced into (in-ns '...) that runs on every tab open, no click needed
    (when-not (re-matches bare-symbol (str (:home-ns config)))
      (throw (ex-info (str "config: :home-ns must be a bare namespace name, got "
                           (pr-str (:home-ns config))) {})))
    config))

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

(defn- send!
  "False, rather than throwing, while the nREPL socket is down (not up yet, or lost and
   awaiting reconnect)."
  [msg]
  (if-let [{:keys [out lock]} @conn]
    (try (locking lock
           (bencode/write-bencode out msg)
           (.flush out))
         true
         (catch java.io.IOException _ false))
    false))

(defn request!
  "Sends `msg` (must carry its own \"id\"), routing every reply to `on-msg` until done.
   False if it couldn't be sent."
  ([msg] (request! msg (constantly nil)))
  ([msg on-msg]
   (let [id (get msg "id")]
     (swap! handlers assoc id (fn [m]
                                (on-msg m)
                                (when (done? m) (swap! handlers dissoc id))))
     (or (send! msg) (do (swap! handlers dissoc id) false)))))

(defn blocking!
  "Sends `msg` (no id needed) and returns every reply up to `done`."
  [msg]
  (let [acc (atom [])
        p   (promise)]
    (if (request! (assoc msg "id" (new-id))
                  (fn [m]
                    (swap! acc conj m)
                    (when (done? m) (deliver p @acc))))
      (deref p 10000 [])
      [])))

(defn new-session! []
  (let [p (promise)]
    (when (request! {"op" "clone" "id" (new-id)}
                    (fn [m] (when-let [s (:new-session m)] (deliver p s))))
      (deref p 5000 nil))))

(defn- read-edn
  "nil if `s` doesn't read. edn/read-string, never read-string: the app produces what we read
   back, but it crosses a socket."
  [s]
  (try (edn/read-string s) (catch Exception _ nil)))

(defn eval-value
  "Blocking-evals `code` in `session` and reads its first :value back as data, or nil if
   there wasn't one or it didn't read as EDN."
  [session code]
  (->> (blocking! {"op" "eval" "code" code "session" session})
       (keep :value)
       first
       read-edn))

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
                  (println "[webrepl] nrepl connection lost:" (.getMessage e))
                  (reset! conn nil)
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
      (def webrepl-lim 200)
      (def webrepl-taps (atom {}))     ; idx -> the tapped value itself, capped
      (def webrepl-tap-n (atom 0))

      (defn webrepl-kind [v]
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

      (def webrepl-branch-kinds
        #{"map" "vector" "set" "seq" "array" "jmap" "jcoll" "object"})

      (defn webrepl-children
        "[{:i idx :label str :v value}] - bounded, never realizes more than webrepl-lim."
        [v]
        (try
          (case (webrepl-kind v)
            ("map" "jmap")
            (map-indexed (fn [i e] {:i i :label (pr-str (key e)) :v (val e)})
                         (take webrepl-lim (seq v)))

            ("vector" "set" "seq" "jcoll" "array")
            (map-indexed (fn [i x] {:i i :label (str i) :v x})
                         (take webrepl-lim (seq v)))

            "object"
            (->> (.getDeclaredFields (class v))
                 (remove #(java.lang.reflect.Modifier/isStatic (.getModifiers %)))
                 (take webrepl-lim)
                 (map-indexed (fn [i f]
                                {:i i :label (.getName f)
                                 :v (try (.setAccessible f true) (.get f v)
                                         (catch Throwable t (str "<inaccessible: "
                                                                 (.getSimpleName (class t)) ">")))})))
            [])
          (catch Throwable t [{:i 0 :label "!" :v (str "<" (.getMessage t) ">")}])))

      (defn webrepl-preview [v]
        (try
          (let [s (binding [*print-length* 12 *print-level* 3] (pr-str v))]
            (if (> (count s) 200) (str (subs s 0 200) "…") s))
          (catch Throwable t (str "<unprintable: " (.getSimpleName (class t)) ">"))))

      (defn webrepl-desc [label v]
        {:label  label
         :kind   (webrepl-kind v)
         :preview (webrepl-preview v)
         ;; branch? by kind, never by counting children: a lazy seq must not be realized
         ;; just to decide whether to draw a disclosure triangle.
         :branch (boolean (webrepl-branch-kinds (webrepl-kind v)))})

      (defn webrepl-node
        "Shallow description of the node at `path` (a vector of child indices) under tap `idx`."
        [idx path]
        (let [root (get @webrepl-taps idx)
              v    (reduce (fn [acc i] (:v (nth (vec (webrepl-children acc)) i nil))) root path)
              kids (vec (webrepl-children v))]
          (assoc (webrepl-desc nil v)
                 :class (when (some? v) (.getName (class v)))
                 :n (count kids)
                 :children (mapv (fn [{:keys [i label v]}]
                                   (assoc (webrepl-desc label v) :i i))
                                 kids))))

      ;; Result store, so "inspect" on a transcript row can name the exact value that row
      ;; produced instead of trusting *1 *2 *3 - which shift under it on every later eval,
      ;; and which the tap it used to send would itself have shifted.
      (def webrepl-results (atom {}))   ; result id -> the value, last 100 kept

      (defn webrepl-keep [rid v]
        (swap! webrepl-results (fn [m] (-> m (assoc rid v) (dissoc (- rid 100)))))
        nil)

      (defn webrepl-tap-result [rid]
        (when-let [e (find @webrepl-results rid)] (tap> (val e)) true))

      ;; The defs above land in whatever namespace this session happens to be in; tab
      ;; sessions live somewhere else entirely, so hand that name back to the bridge and
      ;; let it qualify the calls it makes.
      (str *ns*))))

;; Drains tap> values, emitting one EDN description per value, each terminated by a NUL
;; marker so the bridge can reassemble them out of arbitrarily chunked `out`.
;;  - bounded queue + .offer: a tap storm drops values instead of growing the heap
;;  - fresh queue per run + remove-tap of the previous fn, and the loop exits once it is no
;;    longer the current pump: restarting webrepl against a still-running app would
;;    otherwise leave the old loop alive, competing for the same queue.
(def tap-pump
  (pr-str
   '(do
      (when-let [old (resolve 'webrepl-tap-fn)] (remove-tap @old))
      ;; same for a pump left by the bridge under its former name: nil its queue var so the
      ;; loop exits. Drop once no running app can still carry it.
      (when-let [old (resolve 'replweb-tap-fn)]
        (remove-tap @old)
        (intern *ns* 'replweb-tap-q nil))
      (def webrepl-tap-q (java.util.concurrent.LinkedBlockingQueue. 256))
      (def webrepl-tap-fn (let [q webrepl-tap-q] (fn [v] (.offer q v))))
      (add-tap webrepl-tap-fn)
      (let [q    webrepl-tap-q
            ;; built from (char 0) rather than written literally, so neither this file nor
            ;; the code sent over the wire carries a raw NUL byte
            mark (str (char 0) "END" (char 0))]
        (while (identical? q webrepl-tap-q)
          (when-let [v (.poll q 2 java.util.concurrent.TimeUnit/SECONDS)]
            (let [idx (swap! webrepl-tap-n inc)]
              ;; hold the value for later drill-down, keeping only the last 100
              (swap! webrepl-taps (fn [m] (-> m (assoc idx v) (dissoc (- idx 100)))))
              (print (pr-str (assoc (webrepl-desc nil v) :i idx))))
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
      (when-let [desc (read-edn v)]
        (broadcast! "tap" (assoc desc :at (System/currentTimeMillis)))))))

(defonce ^:private inspect-session (atom nil))
;; Namespace the inspector's vars ended up in, reported back by inspector-setup. Every call
;; the bridge makes into them is qualified with it, since tab sessions sit in home-ns.
(defonce ^:private inspect-ns (atom "user"))

(defn- iq
  "Qualifies an inspector var with the namespace it was actually defined in."
  [sym] (symbol @inspect-ns (name sym)))

(defn start-tap-pump! []
  (if-let [session (new-session!)]
    (do
      ;; The inspector's vars must exist before the pump's loop starts calling webrepl-desc.
      (let [ns (eval-value session inspector-setup)]
        (when (string? ns) (reset! inspect-ns ns)))
      (reset! inspect-session (new-session!))
      ;; Never completes: the loop blocks on .poll, and each tapped value arrives as `out`.
      (request! {"op" "eval" "code" tap-pump "session" session "id" (new-id)}
                (fn [m]
                  (when-let [o (:out m)] (on-tap-chunk o))
                  (when-let [e (:err m)] (println "[webrepl] tap pump:" e)))))
    (println "[webrepl] tap pump unavailable: could not open a session")))

(defn inspect-node
  "Shallow description of the node at `path` under tapped value `idx`, read back as data."
  [idx path]
  (when-let [session @inspect-session]
    (eval-value session (pr-str `(~(iq 'webrepl-node) ~idx ~(vec path))))))

(defn tap-result!
  "Pushes the value a transcript row produced into the inspector, from the inspect session so
   the user's own *1 *2 *3 are left alone."
  [rid]
  (when-let [session @inspect-session]
    (->> (blocking! {"op" "eval" "code" (pr-str `(~(iq 'webrepl-tap-result) ~rid))
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
    (let [wrapped `(binding [*read-eval* false]
                     (let [rdr# (clojure.lang.LineNumberingPushbackReader.
                                 (java.io.StringReader. ~code))]
                       (loop [] (let [f# (read rdr# false :eof)]
                                  (when-not (= f# :eof) (recur))))))
          msgs (blocking! {"op" "eval" "code" (pr-str wrapped) "session" session})
          err  (apply str (keep :err msgs))]
      (cond
        (empty? msgs)   {:ok nil}   ; never sent, or timed out: unknown, not clean
        (some :ex msgs) {:ok false :error (if (seq err) (first (str/split-lines err)) "syntax error")}
        :else           {:ok true}))
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
   startup and it restarting later. A loop, not recursion from the catch (recur can't cross
   it): each failed attempt would otherwise add a stack frame for as long as the JVM is down."
  [host port]
  (loop [wait-ms reconnect-base-ms]
    (when-not (try
                (connect! host port #(connect-with-retry! host port))
                (on-reconnected!)
                true
                (catch Exception e
                  (println (format "[webrepl] nrepl connect failed (%s) - retrying in %dms"
                                   (.getMessage e) wait-ms))
                  false))
      (Thread/sleep ^long wait-ms)
      (recur (min reconnect-max-ms (* wait-ms 2))))))

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
  (if-not (and path (fs/exists? path))
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
                         code   (-> (->> body
                                         (remove str/blank?)
                                         (drop-while title?)       ; leading description
                                         (str/join "\n"))
                                    ;; the block's trailing ")" closes (comment - drop it
                                    (str/replace #"\)\s*$" "")
                                    str/trim)]
                     (when (and (seq code) (seq title))
                       {:title title
                        :code  (str/replace code #"(?m)^  " "")     ; unindent one level
                        :theme (theme-of themes (str title " " code))}))))
           (sort-by (juxt :theme :title))
           vec))))

;; ─────────────────────────────────────────────────────────────────────────────
;; HTTP
;; ─────────────────────────────────────────────────────────────────────────────

;; http-kit leaves the query string raw: parse it once, Ring-style, for every handler.
(defn- wrap-params [handler]
  (fn [req]
    (handler (assoc req :params
                    (->> (str/split (or (:query-string req) "") #"&")
                         (keep #(let [[k v] (str/split % #"=" 2)]
                                  (when (seq k) [k (URLDecoder/decode (or v "") "UTF-8")])))
                         (into {}))))))

(defn- json-res [body]
  {:status 200 :headers {"Content-Type" "application/json; charset=utf-8"
                         "Cache-Control" "no-store"      ; may carry data from a prod JVM
                         "X-Content-Type-Options" "nosniff"}
   :body (json/generate-string body)})

(defn- tab-of [{:keys [params]}]
  (let [id (params "c"), t (get @tabs id)]
    (when (:session t) (assoc t :id id))))

(defn- h-stream [{:keys [params] :as req} nrepl-endpoint home-ns]
  (let [id (params "c")]
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
             (request! {"op" "eval" "code" (pr-str `(in-ns '~(symbol home-ns)))
                        "session" session "id" (new-id)})
             (request! {"op" "eval"
                        "code" (pr-str '[(str (java.net.InetAddress/getLocalHost))
                                         (System/getProperty "env.profile")])
                        "session" session "id" (new-id)}
                       (fn [m]
                         (when-let [v (:value m)]
                           (emit! id "ready" {:ns home-ns :identity v
                                              :nrepl nrepl-endpoint
                                              :boot @boot-id})))))
           (emit! id "ready" {:ns "?" :error "could not open an nREPL session"})))
       :on-close (fn [_ _]
                   (when-let [s (get-in @tabs [id :session])]
                     (request! {"op" "close" "session" s "id" (new-id)}))
                   (swap! tabs dissoc id))})))

(defonce ^:private result-n (atom 0))

(defn- keep-result!
  "Hands the value the row just produced to the app's result store, under `rid`.

   The form ends in *1 so *1 keeps the exact value the user is looking at; only *2 and *3
   take a duplicate. Without this the inspect button had to send (tap> *N) itself, and that
   eval shifted the very history it was counting on - the reason it worked every other click."
  [session rid]
  (request! {"op" "eval" "code" (pr-str `(do (~(iq 'webrepl-keep) ~rid *1) *1))
             "session" session "id" (new-id)}))

(defn- h-eval [{:keys [params] :as req}]
  (if-let [{:keys [id session running]} (tab-of req)]
    (if running
      {:status 429 :body ""}
      (let [code    (slurp (:body req))
            eval-id (new-id)
            rid     (swap! result-n inc)
            t0      (System/nanoTime)
            msg     (cond-> {"op" "eval" "code" code "session" session "id" eval-id}
                      (= "1" (params "pprint"))
                      ;; nrepl.util.print/pprint, not clojure.pprint/pprint: only the former
                      ;; has the [value writer options] arity wrap-print calls, so only it
                      ;; honours :right-margin.
                      (assoc "nrepl.middleware.print/print" "nrepl.util.print/pprint"
                             "nrepl.middleware.print/options" {"right-margin" 92}))]
        (swap! tabs assoc-in [id :running] eval-id)
        (if (request! msg
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
          {:status 202 :body ""}
          (do (swap! tabs assoc-in [id :running] nil)
              {:status 503 :body ""}))))
    {:status 409 :body ""}))

(defn- h-stop [req]
  (if-let [{:keys [session running]} (tab-of req)]
    (do (when running
          (request! {"op" "interrupt" "session" session
                     "interrupt-id" running "id" (new-id)}))
        {:status 202 :body ""})
    {:status 404 :body ""}))

(defn- h-complete [{:keys [params] :as req}]
  (let [{:keys [session ns]} (tab-of req)
        prefix (params "prefix")]
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
  [{:keys [params] :as req}]
  (let [{:keys [session]} (tab-of req)
        sym    (params "sym")
        prefix (params "prefix" "")]
    (json-res
     ;; still validated: (symbol "(x)") prints as the form (x), so data alone isn't enough
     (if (and session sym (re-matches bare-symbol sym))
       (->> (eval-value session
                        (pr-str `(->> (.getMethods (class ~(symbol sym)))
                                      (map (fn [m#] (.getName m#))) distinct sort vec)))
            (filter #(str/starts-with? % prefix))
            vec)
       []))))

(defn- h-lookup [{:keys [params] :as req}]
  (let [{:keys [session ns]} (tab-of req)
        sym (params "sym")]
    (json-res
     (when (and session (seq sym))
       (->> (blocking! {"op" "lookup" "sym" sym "ns" ns "session" session})
            (keep :info)
            (remove empty?)
            first)))))

(defn- h-inspect
  "GET /inspect?i=<tap index>&path=1.0.3 — one level of the tapped value's tree."
  [{:keys [params]}]
  (let [idx  (some-> (params "i") parse-long)
        path (->> (str/split (params "path" "") #"\.")
                  (keep parse-long)
                  vec)]
    (json-res (when idx (inspect-node idx path)))))

(defn- h-inspect-result
  "POST /inspect-result?rid=<result id> — sends that row's value to the inspector."
  [{:keys [params]}]
  (if-let [rid (some-> (params "rid") parse-long)]
    (json-res {:ok (boolean (tap-result! rid))})
    {:status 400 :body ""}))

(defn- h-lint
  "POST /lint — body is a snippet's code; reports whether it reads cleanly, never evals it."
  [req]
  (json-res (lint-code (slurp (:body req)))))

;; Inlined so the tool stays two files. Also the header logo (<img src>), so one drawing.
(def ^:private favicon-svg
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\">"
       "<rect width=\"32\" height=\"32\" rx=\"7\" fill=\"#2B2722\"/>"
       "<g fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2.8\">"
       "<path d=\"M10.5 6.5Q4.5 16 10.5 25.5\" stroke=\"#C9A46B\"/>"
       "<path d=\"M21.5 6.5Q27.5 16 21.5 25.5\" stroke=\"#7FB49C\"/>"
       "<path d=\"M13.5 11.5 18.5 16l-5 4.5\" stroke=\"#D97F5B\" stroke-width=\"3.2\"/>"
       "</g></svg>"))

(defn- h-page [html-path nrepl-endpoint]
  (let [html (if (fs/exists? html-path)
               (slurp html-path)
               (slurp (io/resource "webrepl.html")))
        ;; fresh per response: only the page's own <script> runs, so markup that slips past
        ;; escaping can't execute anything
        nonce (random-hex 128)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"
               "X-Content-Type-Options" "nosniff"
               ;; 'self' blocks any external request, even one a future bug introduces.
               "Content-Security-Policy"
               (str "default-src 'self'; script-src 'nonce-" nonce "'; "
                    "style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self'; "
                    "font-src 'self'; frame-ancestors 'none'; form-action 'self'")}
     :body (-> html
               (str/replace-first "<script>" (str "<script nonce=\"" nonce "\">"))
               (str/replace "__NREPL__" nrepl-endpoint))}))

(defn- same-origin?
  "Localhost binding alone doesn't stop other sites: the user's own browser can still reach
   us via CSRF (cross-site fetch/iframe) or DNS rebinding. Host defeats rebinding; Origin and
   Sec-Fetch-Site (absent on non-browser clients like curl) defeat cross-site requests."
  [{:keys [headers]} port]
  (let [{:strs [host origin sec-fetch-site]} headers]
    (and (#{(str "127.0.0.1:" port) (str "localhost:" port)} host)
         (or (nil? origin) (= origin (str "http://" host)))
         (contains? #{nil "same-origin" "none"} sec-fetch-site))))

;; Same-origin checks stop other sites, not other local processes: anything on this machine
;; can reach 127.0.0.1. The token closes that. Persisted (owner-only) rather than per-run so a
;; bridge restart doesn't log every open tab out; delete the file to rotate it.
(defn- load-or-create-token
  "Anything but 64 hex chars (e.g. left empty by a crash mid-write) is replaced: an empty
   token would match an empty cookie."
  []
  (let [token-file (fs/file (fs/home) ".webrepl-token")
        old        (fs/file (fs/home) ".replweb-token")]
    ;; former name: move rather than orphan a live secret next to the new one
    (when (and (fs/exists? old) (not (fs/exists? token-file)))
      (fs/move old token-file))
    (or (when (fs/exists? token-file)
          (re-matches #"[0-9a-f]{64}" (str/trim (slurp token-file))))
        (let [t (random-hex 256)]
          (fs/delete-if-exists token-file)
          ;; created owner-only up front, never world-readable even briefly
          (fs/create-file token-file {:posix-file-permissions "rw-------"})
          (spit token-file t)
          t))))

(defn- token= [token s]
  (and s (MessageDigest/isEqual (.getBytes ^String token "UTF-8") (.getBytes ^String s "UTF-8"))))

(defn- router [{:keys [html nrepl-endpoint snippets config port token]}]
  (let [home-ns (:home-ns config)
        ;; per port: browsers share localhost cookies across ports
        cookie  (str "webrepl-" port)
        cookie-re (re-pattern (str "(?:^|;\\s*)" cookie "=([^;]*)"))]
    (fn [req]
      (cond
        (not (same-origin? req port))
        {:status 403 :body ""}

        ;; the URL printed at startup: trade the token for a cookie, then drop it from the URL
        (and (= "/" (:uri req)) (token= token (get-in req [:params "token"])))
        {:status 302 :headers {"Location" "/"
                               "Set-Cookie" (str cookie "=" token
                                                 "; Path=/; HttpOnly; SameSite=Strict")}}

        (not (token= token (some->> (get-in req [:headers "cookie"]) (re-find cookie-re) second)))
        {:status 401 :headers {"Content-Type" "text/plain; charset=utf-8"}
         :body "webrepl: open the http://localhost:PORT/?token=... URL printed at startup.\n"}

        :else
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
          "/favicon.svg" {:status 200 :body favicon-svg
                          :headers {"Content-Type" "image/svg+xml"
                                    "X-Content-Type-Options" "nosniff"}}
          "/favicon.ico" {:status 204 :body ""}
          {:status 404 :body ""})))))

;; ─────────────────────────────────────────────────────────────────────────────

(def ^:private cli-spec
  {:nrepl    {:ref "HOST:PORT" :default "127.0.0.1:5555" :desc "nREPL socket to connect to"}
   :port     {:ref "PORT" :default 7899 :coerce :long :desc "HTTP port to serve the console on"}
   :notebook {:ref "PATH" :desc "Clojure file whose (comment ...) forms become snippets; overrides :notebook from config.edn"}
   :config   {:ref "PATH" :default "config.edn" :desc "config.edn to load, see config.example.edn"}
   :help     {:alias :h :coerce :boolean :desc "Show this help and exit"}})

(def ^:private usage
  (str "Usage: webrepl.clj [options]\n\nOptions:\n"
       (cli/format-opts {:spec cli-spec :order [:nrepl :port :notebook :config :help]})))

(defn- parse-args
  "Options, with --nrepl split into :nrepl-host/:nrepl-port. Prints usage and exits on bad
   input (unknown option, missing or malformed value) or --help."
  [args]
  (let [fail          (fn [msg] (binding [*out* *err*] (println msg) (println usage)) (System/exit 2))
        opts          (cli/parse-opts args {:spec cli-spec :restrict true
                                            :error-fn (fn [{:keys [msg]}] (fail msg))})
        [_ host port] (re-matches #"(.+):(\d+)" (str (:nrepl opts)))]
    (cond
      (:help opts) (do (println usage) (System/exit 0))
      (not host)   (fail (str "--nrepl expects HOST:PORT, got " (pr-str (:nrepl opts))))
      :else        (assoc opts :nrepl-host host :nrepl-port (parse-long port)))))

(defn -main [& args]
  (let [{:keys [port nrepl-host nrepl-port notebook] config-path :config} (parse-args args)
        config   (load-config config-path)
        notebook (or notebook (:notebook config))
        themes   (mapv (fn [[title pattern]] [title (re-pattern pattern)]) (:themes config))
        endpoint (str nrepl-host ":" nrepl-port)
        ;; A thunk, not a cached value: editing the notebook shows up on a browser reload.
        snippets #(parse-notebook notebook themes)
        token    (load-or-create-token)]
    ;; Backgrounded: retrying (with the target JVM possibly not up yet) must not hold up the
    ;; HTTP server - tabs already cope with "no session yet" until this succeeds.
    (future (connect-with-retry! nrepl-host nrepl-port))
    ;; next to this script, not the cwd: runnable from anywhere, and a stray webrepl.html in
    ;; whatever directory it's started from is never served as the trusted page
    (hk/run-server (wrap-params
                    (router {:html (str (fs/path (fs/parent (fs/canonicalize *file*)) "webrepl.html"))
                             :nrepl-endpoint endpoint
                             :snippets snippets :config config :port port :token token}))
                   {:port port :ip "127.0.0.1"})
    (println (format "[webrepl] http://localhost:%d/?token=%s  ->  nrepl %s" port token endpoint))
    (println (if notebook
               (format "[webrepl] %d snippets from %s" (count (snippets)) notebook)
               "[webrepl] no notebook configured - see --notebook or :notebook in config.edn"))
    @(promise)))

;; only when run as a script: loading this file into a REPL must not start a server
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
