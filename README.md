# webrepl

A single-file [Babashka](https://babashka.org) web console for a running JVM's in-process
nREPL.

![webrepl demo: eval, pretty-printed results, .method completion, tap> inspector, notebook snippets](docs/demo.gif)

No build step, no dependencies beyond Babashka (tested with v1.13), which bundles the
`org.httpkit`/`bencode`/`cheshire` it uses. Two files: `webrepl.clj` (server +
nREPL client) and `webrepl.html` (the page, inlined CSS/JS, zero external requests - see
Security below).

## Usage

```sh
./webrepl.clj --nrepl 127.0.0.1:5555 [--port 7899] [--notebook PATH] [--config PATH]
```

Then open the `http://localhost:7899/?token=…` URL it prints (once: it's traded for a cookie,
see Security). Each browser tab gets its own nREPL session, dropped
into the configured home namespace. If the target JVM isn't up yet, or its nREPL connection
drops later, the bridge keeps retrying with backoff and reconnects once it's reachable again.

## Features

- **Editor** — Clojure/EDN syntax highlighting, rainbow paren nesting, matching-paren
  highlight, auto-close/type-over/delete-pair for brackets and strings, an arglists strip for
  the enclosing call, ↑↓ recall of your last 100 sent forms.
- **Eval** — streamed over SSE (output/errors/result arrive as produced); `esc` interrupts;
  results can be pretty-printed server-side (toggle, remembered).
- **Completion** — symbols/arglists/docstrings from nREPL core (`nrepl.util.completion`/
  `nrepl.util.lookup`, no cider-nrepl). `.method` completion reflects on the target JVM
  directly once the receiver is bound, e.g. `(def s (get-bean "..."))`, then `(-> s .get█)`.
- **Syntax linting** — the quick-access pane and the main editor are checked against the
  target JVM's own reader (never evaluated), so bad syntax shows up before you run anything.
- **`tap>` inspector** — tapped values show up live; expanding a node fetches only that level
  from the app JVM, so `(tap> (range))` or a Hibernate entity graph is safe to inspect. Any
  transcript result can be sent to the inspector by value identity, not by re-evaluating `*1`.
- **Quick access** — a notebook's `(comment ...)` blocks become one-click snippets, grouped
  by theme (regex patterns, first match wins), searchable; plus pinned shortcuts and starter
  snippets from `config.edn`. `⌘K` toggles the pane.
- **Persistence** — transcript, taps, pane layout and quick-access filter survive a reload
  (localStorage). A bridge restart is detected via a boot id, so stale result ids are marked
  rather than resolved to the wrong value. "clear history" wipes the transcript; "reset
  storage" wipes everything webrepl keeps in the browser.
- **Production awareness** — shows the target JVM's hostname and `env.profile`; if it looks
  like production, the UI switches to a warning palette.

## Security

Binds to `127.0.0.1` only — this is arbitrary eval against a live JVM, never expose it beyond
localhost. Every request must carry a localhost `Host` and, from a browser, a same-origin
`Origin`/`Sec-Fetch-Site` (403 otherwise), so other sites can't reach it through your browser
via CSRF or DNS rebinding. Local processes are kept out by a token: generated once into
`~/.webrepl-token` (owner-only, reused across restarts; delete it to rotate), exchanged on first
visit for an `HttpOnly; SameSite=Strict` cookie, required on every request (401 otherwise).
The page makes no external network requests (system fonts, no CDN) and is served with a
same-origin `Content-Security-Policy` header: scripts need a per-response nonce, framing is
denied. Transcripts persist raw values, never markup.

## Configuration

Project-specific bits (title, home namespace, a notebook of reusable snippets, pinned
quick-access shortcuts) live in `config.edn`, not in the source. Copy
[`config.example.edn`](config.example.edn) to `config.edn` (gitignored) and adapt it:

- `:home-ns` — namespace each browser tab's session starts in.
- `:notebook` — path to a `.clj` file whose top-level `(comment ...)` forms are parsed into
  snippets, grouped by theme, and served from `/snippets`. Editing it shows up on reload.
- `:themes` — `[title pattern]` pairs classifying notebook snippets, first match wins.
  Optional; defaults to a generic set (database, HTTP, jobs, cache, ...) - override to match
  your own app's vocabulary.
- `:quick-access` — `[title code]` pairs pinned at the top of the quick-access pane, e.g.
  shortcuts to your app's hot-reloadable config vars.
- `:starters` — snippets to try before anything else has run.

`--config PATH` overrides the default `config.edn` next to the script; `--notebook`
overrides `:notebook`.

## License

Copyright (c) 2026 Olivier G.

This program and the accompanying materials are made available under the terms of the
Eclipse Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0
(see [LICENSE](LICENSE)).

This Source Code may also be made available under the following Secondary Licenses when the
conditions for such availability set forth in the Eclipse Public License, v. 2.0 are
satisfied: GNU General Public License as published by the Free Software Foundation, either
version 2 of the License, or (at your option) any later version, with the GNU Classpath
Exception which is available at https://www.gnu.org/software/classpath/license.html.

SPDX: `EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0`
