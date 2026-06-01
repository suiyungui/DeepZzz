# K2 Edge Project

This is the unified project root for the DeepZZZ K2 board service.

```text
K2/
├── main.py                  # service entry point
├── config.py                # config.json + env + CLI parsing
├── config.json              # board defaults
├── devices/                 # hardware capture and GPIO control
├── ai/                      # model inference and runtime provider boundaries
├── pipelines/               # video/audio business pipelines
├── network/                 # HTTP API, HLS, static file serving
├── services/                # runtime state and resource monitoring
├── utils/                   # shared paths and metrics
├── web/                     # frontend templates and static assets
├── models/                  # local model assets
├── scripts/                 # board scripts and systemd units
└── runtime/                 # generated files, not committed
```

## Runtime Flow

Video:

```text
devices.camera
  -> pipelines.video_pipeline
  -> ai.vision_ai
  -> network.edge_http (/api/detections, /api/snapshot, /hls)
```

Audio:

```text
devices.mic
  -> pipelines.audio_pipeline
  -> ai.audio_ai
  -> network.edge_http (/api/status, /api/audio/yamnet)
```

Day/night and IR-CUT:

```text
scripts/day_night.py
  -> devices.day_night_gpio
  -> runtime/state/day_night
  -> devices.ircut
  -> scripts/ir_cut_control.py
```

## Board Services

- `deepzzz-k2-edge.service` starts `/home/z/main.py`.
- `day-night-sync.service` starts `/home/z/scripts/day_night.py sync-ircut --sync-camera`.
- Runtime logs and state are stored under `/home/z/runtime/`.

## NPU Migration Boundary

Put CPU/NPU provider selection, benchmark, and fallback logic in `ai/runtime.py`.
Business code should receive a provider name from config and should not import
NPU-specific packages directly outside the runtime boundary.

Current pose default on the board uses the CPU FP32 model:

```text
POSE_MODEL=/home/z/.brdk_models/pose/yolov8n-pose-320.onnx
POSE_PROVIDER=CPUExecutionProvider
POSE_PROVIDER_FALLBACK=0
LOW_LIGHT_MODE=off
LOW_LIGHT_DESATURATE=0
```

Night grayscale is handled by the camera UVC saturation control in
`day-night-sync.service`; software low-light enhancement is disabled by default.

Use `scripts/benchmark_pose.py` on the board before changing pose models.
