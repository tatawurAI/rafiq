#!/bin/sh
# Starts a bridge archive with an empty environment and no JDK on PATH, then checks GET /health.
# Usage: scripts/smoke-bridge-archive.sh <path to rafiq-bridge-<version>-<os>-<arch>.tar.gz>
# It uses a temporary data folder and a random high port, and always stops the bridge it started.
set -eu

archive=${1:?usage: smoke-bridge-archive.sh <archive.tar.gz>}
work=$(mktemp -d "${TMPDIR:-/tmp}/rafiq-smoke.XXXXXX")
pid=""

cleanup() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT INT TERM

tar -xzf "$archive" -C "$work"
home=$(find "$work" -mindepth 1 -maxdepth 1 -type d | head -n 1)
mkdir "$work/data" "$work/home"

# A port from 20000 to 59999. If it is taken, the bridge fails to start and the check fails loudly.
port=$(awk 'BEGIN { srand(); print 20000 + int(rand() * 40000) }')

# env -i drops JAVA_HOME and every other variable. PATH holds only the system folders.
env -i PATH=/usr/bin:/bin HOME="$work/home" \
    RAFIQ_PORT="$port" RAFIQ_DATA_DIR="$work/data" \
    "$home/bin/rafiq-bridge" >"$work/bridge.log" 2>&1 &
pid=$!

body=""
i=0
while [ "$i" -lt 60 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "The bridge exited early. Log:" >&2
        cat "$work/bridge.log" >&2
        exit 1
    fi
    if body=$(curl --silent --fail --max-time 2 "http://127.0.0.1:$port/health"); then
        break
    fi
    body=""
    i=$((i + 1))
    sleep 1
done

case $body in
    *'"status":"ok"'*) echo "OK: /health answered $body" ;;
    *)
        echo "No healthy answer from /health. Log:" >&2
        cat "$work/bridge.log" >&2
        exit 1
        ;;
esac
