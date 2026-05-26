# DeepZZZ K2 Edge

K2/Bianbu board runtime for DeepZZZ-style video preview and low-rate recognition.

## Design

- One `ffmpeg` process owns the USB camera.
- High quality preview is copied from the camera H.264 stream into HLS.
- An optional low-resolution branch can be piped to Python at a reduced frame rate for recognition.
- The web page displays the high quality HLS preview and overlays recognition JSON on top.
- Audio capture uses ALSA `arecord` so it works without a Linux desktop session.
- Future ONNX models should use `SpaceMITExecutionProvider` through `deepzzz_k2/onnx_runtime.py`.

## Board Start

```bash
cd /home/z/deepzzz_k2_edge
bash scripts/start.sh
```

Open the printed URL from a computer on the same LAN.

Common options:

```bash
PREVIEW_SIZE=1280x720 PREVIEW_FPS=30 bash scripts/start.sh
ANALYSIS_WIDTH=320 ANALYSIS_FPS=2 bash scripts/start.sh
ANALYSIS_WIDTH=320 ANALYSIS_FPS=1 VIDEO_DECODER=h264_stcodec bash scripts/start.sh
ENABLE_POSE=1 ANALYSIS_WIDTH=320 ANALYSIS_FPS=1 VIDEO_DECODER=h264_stcodec bash scripts/start.sh
PORT=7861 bash scripts/start.sh
AUDIO_DEVICE=plughw:0,0 bash scripts/start.sh
```

Stop:

```bash
bash scripts/stop.sh
```

If the old `/home/z/camera-web` service is still running, it will occupy the same
USB camera. Stop that service before starting this project:

```bash
cd /home/z/camera-web
./stop_camera_web.sh
pkill -f "/home/z/camera-web/camera_server.py" || true
pkill -f "ffmpeg .*camera-web/hls" || true
```

## Board Checks

```bash
bash scripts/check_board.sh
```

If microphone capture fails for user `z`, add it to the audio group and log in again:

```bash
sudo usermod -aG audio z
```

## Migration Notes

This folder is self-contained. After reinstalling the board, copy it to `/home/z/deepzzz_k2_edge`, install the Bianbu packages listed in `requirements-board.txt`, and run `scripts/start.sh`.

The current built-in vision worker is a low-resolution diagnostics worker. Replace `deepzzz_k2/vision.py` with ONNX model inference when the target models are selected.

Validated on the current board:

- HLS preview: `1280x720 @ 30fps`.
- Low-resolution analysis branch: disabled by default with `ANALYSIS_FPS=0`; enable it when a model is active.
- Audio capture: `arecord`, `plughw:0,0`, mono `16000 Hz`.
- ONNX Runtime providers: `SpaceMITExecutionProvider`, `CPUExecutionProvider`.

When `ANALYSIS_FPS` is greater than `0`, ffmpeg decodes the preview stream for
the low-resolution analysis branch, so it costs more CPU than the preview-only
`-c:v copy` pipeline. Keep analysis width and FPS low until the final ONNX model
is selected.

On the current K2 board, `h264_stcodec` is available and initializes the
SpacemiT MPP/VPU decoder through `/dev/video0`. Use `VIDEO_DECODER=h264_stcodec`
when enabling the low-resolution analysis branch.
