#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BASE_DIR"

for pid_file in ffmpeg.pid web.pid stream.pid; do
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file")"
    if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "$pid_file"
  fi
done

pkill -TERM -f "ffmpeg .* -f mpjpeg" >/dev/null 2>&1 || true
pkill -TERM -f "ffmpeg .*hls/stream.m3u8" >/dev/null 2>&1 || true
