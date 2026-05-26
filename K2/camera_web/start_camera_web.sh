#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
WEB_PORT="${WEB_PORT:-8080}"
DEVICE="${DEVICE:-/dev/v4l/by-id/usb-HDMI_3.0_USB_Camera_HDMI_USB_Camera_2023121018-video-index0}"
SIZE="${SIZE:-1280x720}"
FPS="${FPS:-30}"
HLS_TIME="${HLS_TIME:-1}"
HLS_LIST_SIZE="${HLS_LIST_SIZE:-5}"

cd "$BASE_DIR"
mkdir -p logs hls

./stop_camera_web.sh >/dev/null 2>&1 || true
sleep 1
rm -f hls/stream.m3u8 hls/segment_*.ts

nohup ffmpeg \
  -hide_banner \
  -loglevel warning \
  -fflags nobuffer \
  -flags low_delay \
  -f v4l2 \
  -input_format h264 \
  -video_size "$SIZE" \
  -framerate "$FPS" \
  -i "$DEVICE" \
  -an \
  -c:v copy \
  -f hls \
  -hls_time "$HLS_TIME" \
  -hls_list_size "$HLS_LIST_SIZE" \
  -hls_delete_threshold 2 \
  -hls_flags delete_segments+omit_endlist+independent_segments+program_date_time \
  -hls_segment_filename "hls/segment_%05d.ts" \
  "hls/stream.m3u8" \
  > logs/ffmpeg-hls.log 2>&1 &
echo "$!" > ffmpeg.pid

for _ in $(seq 1 50); do
  [ -s hls/stream.m3u8 ] && break
  sleep 0.1
done

nohup env WEB_PORT="$WEB_PORT" \
  python3 camera_server.py \
  > logs/web.log 2>&1 &
echo "$!" > web.pid

echo "Web page: http://$(hostname -I | awk '{print $1}'):${WEB_PORT}/"
echo "Stream:   http://$(hostname -I | awk '{print $1}'):${WEB_PORT}/hls/stream.m3u8"
echo "Mode:     H.264 camera input -> HLS segments"
