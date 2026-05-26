#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
SIZE="${SIZE:-1280x720}"
FPS="${FPS:-30}"
HLS_TIME="${HLS_TIME:-1}"
HLS_LIST_SIZE="${HLS_LIST_SIZE:-5}"
DEVICE_OVERRIDE="${DEVICE:-}"

find_device() {
  if [ -n "$DEVICE_OVERRIDE" ] && [ -e "$DEVICE_OVERRIDE" ]; then
    echo "$DEVICE_OVERRIDE"
    return 0
  fi

  shopt -s nullglob
  for p in /dev/v4l/by-id/*video-index0; do
    case "$p" in
      *USB*|*usb*|*Camera*|*camera*|*HDMI*|*hdmi*)
        echo "$p"
        return 0
        ;;
    esac
  done

  for p in /dev/v4l/by-path/*usb*video-index*; do
    echo "$p"
    return 0
  done

  for dev in /dev/video*; do
    [ -e "$dev" ] || continue
    info="$(udevadm info -q property -n "$dev" 2>/dev/null || true)"
    printf '%s
' "$info" | grep -q '^ID_BUS=usb$' || continue
    echo "$dev"
    return 0
  done

  return 1
}

cd "$BASE_DIR"
mkdir -p logs hls

while true; do
  rm -f hls/stream.m3u8 hls/segment_*.ts
  if DEVICE_PATH="$(find_device)"; then
    echo "$(date '+%F %T') using device: $DEVICE_PATH"
    exec /usr/bin/ffmpeg       -hide_banner       -loglevel warning       -fflags nobuffer       -flags low_delay       -f v4l2       -input_format h264       -video_size "$SIZE"       -framerate "$FPS"       -i "$DEVICE_PATH"       -an       -c:v copy       -f hls       -hls_time "$HLS_TIME"       -hls_list_size "$HLS_LIST_SIZE"       -hls_delete_threshold 2       -hls_flags delete_segments+omit_endlist+independent_segments+program_date_time       -hls_segment_filename "$BASE_DIR/hls/segment_%05d.ts"       "$BASE_DIR/hls/stream.m3u8"
  fi

  echo "$(date '+%F %T') camera device not found, retrying in 2s"
  sleep 2
 done
