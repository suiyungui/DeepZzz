from __future__ import annotations

import argparse
import json
import mimetypes
import os
import signal
import subprocess
import threading
import time
import wave
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import cv2
import numpy as np

from deepzzz_k2.audio import record_audio_level
from deepzzz_k2.camera import CameraPipeline
from deepzzz_k2.resources import ResourceMonitor
from deepzzz_k2.vision import VisionWorker
from deepzzz_k2.yamnet_audio import YamnetCryDetector, record_waveform


BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
TEMPLATES_DIR = BASE_DIR / "templates"
RUNTIME_DIR = BASE_DIR / "runtime"
HLS_DIR = RUNTIME_DIR / "hls"
AUDIO_DIR = RUNTIME_DIR / "audio"
LOG_DIR = RUNTIME_DIR / "logs"

mimetypes.add_type("application/vnd.apple.mpegurl", ".m3u8")
mimetypes.add_type("video/mp2t", ".ts")


class EdgeState:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.vision = VisionWorker(
            model_path=args.pose_model,
            enabled=args.enable_pose,
            provider=args.pose_provider,
        )
        self.camera = CameraPipeline(
            hls_dir=HLS_DIR,
            log_dir=LOG_DIR,
            frame_callback=self.vision.submit_frame,
            device=args.device,
            preview_size=args.preview_size,
            preview_fps=args.preview_fps,
            hls_time=args.hls_time,
            hls_list_size=args.hls_list_size,
            analysis_width=args.analysis_width,
            analysis_fps=args.analysis_fps,
            video_decoder=args.video_decoder,
        )
        self.started_at = time.time()
        self.resources = ResourceMonitor()
        self.audio_lock = threading.Lock()
        self.latest_audio: dict[str, Any] | None = None
        self.latest_yamnet: dict[str, Any] | None = None
        self._yamnet: YamnetCryDetector | None = None

    def start(self) -> None:
        self.vision.start()
        if self.args.start_camera:
            try:
                self.camera.start()
            except Exception as exc:
                print(f"camera start failed: {exc}", flush=True)

    def stop(self) -> None:
        self.camera.stop()
        self.vision.stop()

    def status(self) -> dict[str, Any]:
        return {
            "ok": True,
            "uptime_s": round(time.time() - self.started_at, 1),
            "camera": self.camera.status(),
            "vision": self.vision.status(),
            "resources": self.resources.snapshot(),
            "audio": self.latest_audio,
            "yamnet": self.latest_yamnet,
            "config": {
                "preview_size": self.args.preview_size,
                "preview_fps": self.args.preview_fps,
                "analysis_width": self.args.analysis_width,
                "analysis_fps": self.args.analysis_fps,
                "video_decoder": self.args.video_decoder,
                "pose_model": self.args.pose_model,
                "enable_pose": self.args.enable_pose,
                "pose_provider": self.args.pose_provider,
                "audio_device": self.args.audio_device,
                "yamnet_model": self.args.yamnet_model,
            },
        }

    def record_audio(self, seconds: float) -> dict[str, Any]:
        seconds = max(0.5, min(float(seconds), 10.0))
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        output = AUDIO_DIR / f"mic_{int(time.time() * 1000)}.wav"
        with self.audio_lock:
            result = record_audio_level(
                output,
                seconds=seconds,
                device=self.args.audio_device,
                sample_rate=self.args.audio_rate,
            )
            self.latest_audio = result
            return result

    def analyze_cry(self, seconds: float) -> dict[str, Any]:
        seconds = max(1.0, min(float(seconds), 10.0))
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        output = AUDIO_DIR / f"yamnet_{int(time.time() * 1000)}.wav"
        with self.audio_lock:
            waveform, rate = record_waveform(
                seconds=seconds,
                device=self.args.audio_device,
                output=output,
                sample_rate=self.args.audio_rate,
            )
            if self._yamnet is None:
                self._yamnet = YamnetCryDetector(self.args.yamnet_model, self.args.yamnet_labels)
            result = self._yamnet.infer_waveform(waveform, rate)
            result["file"] = str(output)
            result["audio_url"] = f"/audio/{output.name}"
            self.latest_yamnet = result
            return result


STATE: EdgeState | None = None


class Handler(SimpleHTTPRequestHandler):
    server_version = "DeepZZZK2/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.log_date_time_string()} {self.address_string()} {fmt % args}", flush=True)

    def end_headers(self) -> None:
        if self.path.endswith((".m3u8", ".ts")):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Pragma", "no-cache")
            self.send_header("Expires", "0")
        super().end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/":
            self._send_file(TEMPLATES_DIR / "index.html", "text/html; charset=utf-8")
        elif path.startswith("/static/"):
            self._send_file(STATIC_DIR / path.removeprefix("/static/"))
        elif path.startswith("/hls/"):
            self._send_file(HLS_DIR / path.removeprefix("/hls/"))
        elif path.startswith("/audio/"):
            self._send_file(AUDIO_DIR / path.removeprefix("/audio/"))
        elif path == "/api/status":
            self._send_json(self._state().status())
        elif path == "/api/detections":
            self._send_json({"ok": True, "result": self._state().vision.latest_result()})
        elif path == "/api/snapshot":
            jpg = self._state().vision.latest_jpeg()
            if jpg is None:
                self.send_error(HTTPStatus.NOT_FOUND, "no analysis frame yet")
            else:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(jpg)))
                self.end_headers()
                self.wfile.write(jpg)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        body = self._read_json()
        state = self._state()
        try:
            if path == "/api/camera/start":
                state.camera.start()
                self._send_json({"ok": True, "camera": state.camera.status()})
            elif path == "/api/camera/stop":
                state.camera.stop()
                self._send_json({"ok": True, "camera": state.camera.status()})
            elif path == "/api/vision/start":
                state.vision.start()
                self._send_json({"ok": True, "vision": state.vision.status()})
            elif path == "/api/vision/stop":
                state.vision.stop()
                self._send_json({"ok": True, "vision": state.vision.status()})
            elif path == "/api/audio/record":
                seconds = float(body.get("seconds", 2.0))
                result = state.record_audio(seconds)
                self._send_json({"ok": True, "result": result})
            elif path == "/api/audio/yamnet":
                seconds = float(body.get("seconds", 2.0))
                result = state.analyze_cry(seconds)
                self._send_json({"ok": True, "result": result})
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=500)

    def _state(self) -> EdgeState:
        if STATE is None:
            raise RuntimeError("server state is not initialized")
        return STATE

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            return {}

    def _send_json(self, payload: dict[str, Any], status: int = 200) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_file(self, path: Path, content_type: str | None = None) -> None:
        resolved = path.resolve()
        allowed_roots = [STATIC_DIR.resolve(), TEMPLATES_DIR.resolve(), HLS_DIR.resolve(), AUDIO_DIR.resolve()]
        if not any(str(resolved).startswith(str(root)) for root in allowed_roots):
            self.send_error(HTTPStatus.FORBIDDEN)
            return
        if not resolved.exists() or not resolved.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        data = resolved.read_bytes()
        if content_type is None:
            content_type = mimetypes.guess_type(str(resolved))[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="DeepZZZ K2 edge service")
    parser.add_argument("--host", default=os.environ.get("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "7860")))
    parser.add_argument("--device", default=os.environ.get("CAMERA_DEVICE", "auto"))
    parser.add_argument("--preview-size", default=os.environ.get("PREVIEW_SIZE", "1280x720"))
    parser.add_argument("--preview-fps", type=int, default=int(os.environ.get("PREVIEW_FPS", "30")))
    parser.add_argument("--analysis-width", type=int, default=int(os.environ.get("ANALYSIS_WIDTH", "320")))
    parser.add_argument("--analysis-fps", type=float, default=float(os.environ.get("ANALYSIS_FPS", "0")))
    parser.add_argument("--video-decoder", default=os.environ.get("VIDEO_DECODER", "auto"))
    parser.add_argument("--hls-time", type=float, default=float(os.environ.get("HLS_TIME", "1")))
    parser.add_argument("--hls-list-size", type=int, default=int(os.environ.get("HLS_LIST_SIZE", "5")))
    parser.add_argument("--audio-device", default=os.environ.get("AUDIO_DEVICE", "plughw:0,0"))
    parser.add_argument("--audio-rate", type=int, default=int(os.environ.get("AUDIO_RATE", "16000")))
    parser.add_argument("--yamnet-model", default=os.environ.get("YAMNET_MODEL", "models/yamnet/yamnet.onnx"))
    parser.add_argument("--yamnet-labels", default=os.environ.get("YAMNET_LABELS", "models/yamnet/yamnet_class_map.csv"))
    parser.add_argument("--pose-model", default=os.environ.get("POSE_MODEL", "/home/z/.brdk_models/pose/yolov8n-pose-320.onnx"))
    parser.add_argument("--pose-provider", default=os.environ.get("POSE_PROVIDER", "CPUExecutionProvider"))
    parser.add_argument("--enable-pose", action="store_true", default=os.environ.get("ENABLE_POSE", "0") == "1")
    parser.add_argument("--no-camera", dest="start_camera", action="store_false")
    parser.set_defaults(start_camera=True)
    return parser.parse_args()


def main() -> None:
    global STATE
    args = parse_args()
    for path in (HLS_DIR, AUDIO_DIR, LOG_DIR):
        path.mkdir(parents=True, exist_ok=True)
    STATE = EdgeState(args)

    def shutdown(signum: int, frame: Any) -> None:
        STATE.stop()
        raise SystemExit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    STATE.start()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"DeepZZZ K2 edge service listening on {args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    finally:
        STATE.stop()


if __name__ == "__main__":
    main()
