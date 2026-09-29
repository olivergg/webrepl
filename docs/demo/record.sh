#!/usr/bin/env bash
# Re-records docs/demo.gif: starts the demo app's nREPL, a webrepl bridge on it, drives the page
# in headless Chrome (record.js) and converts the video to a GIF.
# Needs: clojure CLI, bb, node, ffmpeg, Google Chrome (or CHROME=/path/to/chrome).
set -euo pipefail
cd "$(dirname "$0")"
NREPL_PORT=${NREPL_PORT:-1668}
HTTP_PORT=${HTTP_PORT:-7912}
home=$(mktemp -d)        # throwaway token dir: never touches your ~/.webrepl-token
pids=()
# children too: the clojure launcher runs java as a child rather than exec'ing it
cleanup() { for p in ${pids[@]+"${pids[@]}"}; do pkill -P "$p" || true; kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# a stale app JVM on the port would be recorded with whatever state it's in
for port in "$NREPL_PORT" "$HTTP_PORT"; do
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then echo "port $port already in use" >&2; exit 1; fi
done
[ -d node_modules/playwright-core ] || npm install --silent
rm -rf out

clojure -Sdeps '{:paths ["src"] :deps {nrepl/nrepl {:mvn/version "1.3.1"}}}' \
  -M -e "(require 'demo.app)" -m nrepl.cmdline --port "$NREPL_PORT" > nrepl.log 2>&1 &
pids+=($!)
until nc -z 127.0.0.1 "$NREPL_PORT" 2>/dev/null; do sleep 1; done

bb -Duser.home="$home" ../../webrepl.clj --port "$HTTP_PORT" --nrepl "127.0.0.1:$NREPL_PORT" \
  --config demo.config.edn > bridge.log 2>&1 &
pids+=($!)
until [ -s "$home/.webrepl-token" ] && nc -z 127.0.0.1 "$HTTP_PORT" 2>/dev/null; do sleep 0.5; done

video=$(node record.js "http://localhost:$HTTP_PORT/?token=$(cat "$home/.webrepl-token")" out)
ffmpeg -v error -y -ss 0.3 -i "$video" -loop 0 -vf \
  "fps=12,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  ../demo.gif
echo "wrote docs/demo.gif ($(du -h ../demo.gif | cut -f1))"
