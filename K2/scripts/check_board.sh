#!/usr/bin/env bash
set -euo pipefail

echo "=== system ==="
uname -a
cat /etc/os-release 2>/dev/null | sed -n '1,8p' || true

echo "=== python ==="
python3 --version
python3 - <<'PY'
import importlib
for name in ["cv2", "numpy", "onnxruntime", "spacemit_ort"]:
    try:
        mod = importlib.import_module(name)
        print(name, "OK", getattr(mod, "__version__", ""))
    except Exception as exc:
        print(name, "FAIL", type(exc).__name__, exc)
try:
    import spacemit_ort  # noqa
    import onnxruntime as ort
    print("providers", ort.get_available_providers())
except Exception:
    pass
PY

echo "=== pose cpu benchmark ==="
if [ -f /home/z/.brdk_models/pose/yolov8n-pose-320.onnx ]; then
  python3 /home/z/scripts/benchmark_pose.py --model /home/z/.brdk_models/pose/yolov8n-pose-320.onnx --provider CPUExecutionProvider --no-fallback --runs 3 || true
else
  echo "pose fp32 model missing: /home/z/.brdk_models/pose/yolov8n-pose-320.onnx"
fi

echo "=== camera ==="
ls -l /dev/video* 2>/dev/null || true
command -v v4l2-ctl >/dev/null && v4l2-ctl --list-devices || true

echo "=== audio ==="
id
cat /proc/asound/cards 2>/dev/null || true
arecord -l 2>&1 || true
