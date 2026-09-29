# Demo recording

Sources of [`../demo.gif`](../demo.gif): a pretend order-management app (`src/demo/app.clj`), its
snippet notebook, a webrepl config, and a Playwright script driving the page.

```sh
docs/demo/record.sh
```

Starts the app's nREPL (port 1668) and a bridge on it (port 7912, throwaway token), plays the
scenario in headless Chrome and rewrites `docs/demo.gif`. Needs the Clojure CLI, `bb`, Node,
`ffmpeg` and Google Chrome (`CHROME=/path/to/chrome` to use another). The recording machine's
hostname is replaced by `demo-app/10.0.0.42` before it is ever drawn.

Edit the scenario in `record.js`.
