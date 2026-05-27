from __future__ import annotations

import argparse
from collections import deque
import json
import mimetypes
import os
import signal
import subprocess
import threading
import time
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import numpy as np

from deepzzz_k2.audio import record_audio_level
from deepzzz_k2.camera import CameraPipeline
from deepzzz_k2.resources import ResourceMonitor
from deepzzz_k2.vision import VisionWorker
from deepzzz_k2.yamnet_audio import (
    ContinuousWaveformRecorder,
    CryStateTracker,
    YamnetCryDetector,
    record_waveform,
    write_waveform,
)


BASE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BASE_DIR.parent
STATIC_DIR = BASE_DIR / "static"
TEMPLATES_DIR = BASE_DIR / "templates"
RUNTIME_DIR = BASE_DIR / "runtime"
HLS_DIR = RUNTIME_DIR / "hls"
AUDIO_DIR = RUNTIME_DIR / "audio"
LOG_DIR = RUNTIME_DIR / "logs"
DAY_NIGHT_SCRIPT = PROJECT_ROOT / "day_night" / "day_night.py"
DAY_NIGHT_LOG = PROJECT_ROOT / "day_night" / "logs" / "sync_day_night_ircut.log"
IRCUT_MODE_FILE = PROJECT_ROOT / "ir-cut" / "state" / "current_mode"

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
        self.yamnet_lock = threading.Lock()
        self.latest_audio: dict[str, Any] | None = None
        self.latest_yamnet: dict[str, Any] | None = None
        self._yamnet: YamnetCryDetector | None = None
        self._audio_recorder: ContinuousWaveformRecorder | None = None
        self._cry_state = CryStateTracker()
        self._yamnet_checks = 0
        self._yamnet_checked_at: deque[float] = deque(maxlen=120)
        self._yamnet_stop = threading.Event()
        self._yamnet_thread: threading.Thread | None = None

    def start(self) -> None:
        self.vision.start()
        if self.args.enable_yamnet:
            self.start_yamnet_loop()
        if self.args.start_camera:
            try:
                self.camera.start()
            except Exception as exc:
                print(f"camera start failed: {exc}", flush=True)

    def stop(self) -> None:
        self.stop_yamnet_loop()
        self.camera.stop()
        self.vision.stop()

    def start_yamnet_loop(self) -> None:
        if self._yamnet_thread and self._yamnet_thread.is_alive():
            return
        self._yamnet_stop.clear()
        self._yamnet_thread = threading.Thread(target=self._yamnet_loop, name="yamnet-loop", daemon=True)
        self._yamnet_thread.start()

    def stop_yamnet_loop(self) -> None:
        self._yamnet_stop.set()
        if self._yamnet_thread and self._yamnet_thread.is_alive():
            self._yamnet_thread.join(timeout=2)
        if self._audio_recorder is not None:
            self._audio_recorder.stop()

    def _yamnet_loop(self) -> None:
        while not self._yamnet_stop.is_set():
            try:
                self._ensure_audio_recorder()
                waveform, rate = self._latest_yamnet_window()
                min_samples = int(self.args.audio_rate * min(1.0, float(self.args.yamnet_seconds)))
                if waveform.size < min_samples:
                    previous = self.latest_yamnet or {}
                    self.latest_yamnet = {
                        **previous,
                        "status": "warming",
                        "capture": self._audio_recorder.status() if self._audio_recorder else None,
                        "updated_at": time.time(),
                    }
                else:
                    self._analyze_cry_waveform(waveform, rate, save_audio=False)
            except Exception as exc:
                self.latest_yamnet = {
                    "status": "error",
                    "error": str(exc),
                    "capture": self._audio_recorder.status() if self._audio_recorder else None,
                    "updated_at": time.time(),
                }
                if self._audio_recorder is not None:
                    self._audio_recorder.stop()
                    self._audio_recorder = None
                if self._yamnet_stop.wait(1.0):
                    break
                continue
            self._yamnet_stop.wait(max(0.1, float(self.args.yamnet_interval)))

    def status(self) -> dict[str, Any]:
        now = time.time()
        vision = self.vision.status()
        resources = self.resources.snapshot()
        yamnet = self.latest_yamnet
        return {
            "ok": True,
            "uptime_s": round(now - self.started_at, 1),
            "camera": self.camera.status(),
            "vision": vision,
            "resources": resources,
            "audio": self.latest_audio,
            "yamnet": yamnet,
            "day_night": day_night_status(),
            "summary": self._summary(vision, yamnet, now),
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
                "enable_yamnet": self.args.enable_yamnet,
                "yamnet_seconds": self.args.yamnet_seconds,
                "yamnet_interval": self.args.yamnet_interval,
            },
        }

    def _summary(self, vision: dict[str, Any], yamnet: dict[str, Any] | None, now: float) -> dict[str, Any]:
        latest = vision.get("latest") or {}
        video_updated_at = latest.get("updated_at")
        audio_updated_at = yamnet.get("updated_at") if yamnet else None
        return {
            "video_age_s": age_seconds(video_updated_at, now),
            "video_fps": vision.get("actual_fps"),
            "audio_age_s": age_seconds(audio_updated_at, now),
            "audio_rate_hz": recent_rate(self._yamnet_checked_at, now=now),
        }

    def record_audio(self, seconds: float) -> dict[str, Any]:
        seconds = max(0.5, min(float(seconds), 10.0))
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        output = AUDIO_DIR / f"mic_{int(time.time() * 1000)}.wav"
        if self._audio_recorder and self._audio_recorder.running:
            waveform, rate = self._audio_recorder.latest_window(seconds)
            write_waveform(output, waveform, rate)
            result = self._audio_level_from_waveform(output, waveform, rate)
            self.latest_audio = result
            return result
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
        if self._audio_recorder and self._audio_recorder.running:
            waveform, rate = self._audio_recorder.latest_window(seconds)
            return self._analyze_cry_waveform(waveform, rate, save_audio=True)
        with self.audio_lock:
            output = AUDIO_DIR / "yamnet_latest.wav"
            waveform, rate = record_waveform(
                seconds=seconds,
                device=self.args.audio_device,
                output=output,
                sample_rate=self.args.audio_rate,
            )
            return self._analyze_cry_waveform(waveform, rate, output=output, save_audio=False)

    def _ensure_audio_recorder(self) -> None:
        if self._audio_recorder is None:
            buffer_seconds = max(8.0, float(self.args.yamnet_seconds) * 3.0)
            self._audio_recorder = ContinuousWaveformRecorder(
                device=self.args.audio_device,
                sample_rate=self.args.audio_rate,
                buffer_seconds=buffer_seconds,
                chunk_seconds=max(0.05, min(0.25, float(self.args.yamnet_interval))),
            )
        if not self._audio_recorder.running:
            self._audio_recorder.start()

    def _latest_yamnet_window(self) -> tuple[np.ndarray, int]:
        if self._audio_recorder is None:
            raise RuntimeError("audio recorder is not initialized")
        return self._audio_recorder.latest_window(self.args.yamnet_seconds)

    def _analyze_cry_waveform(
        self,
        waveform: np.ndarray,
        rate: int,
        output: Path | None = None,
        save_audio: bool = True,
    ) -> dict[str, Any]:
        if output is None:
            output = AUDIO_DIR / "yamnet_latest.wav"
        if save_audio:
            write_waveform(output, waveform, rate)
        with self.yamnet_lock:
            if self._yamnet is None:
                self._yamnet = YamnetCryDetector(self.args.yamnet_model, self.args.yamnet_labels)
            result = self._yamnet.infer_waveform(waveform, rate)
            result.update(self._cry_state.update(float(result["cry_score"])))
            self._yamnet_checks += 1
            self._yamnet_checked_at.append(time.time())
            result["checks"] = self._yamnet_checks
        result["file"] = str(output)
        result["audio_url"] = f"/audio/{output.name}"
        result["status"] = "ok"
        result["capture"] = self._audio_recorder.status() if self._audio_recorder else None
        self.latest_yamnet = result
        return result

    def _audio_level_from_waveform(self, output: Path, waveform: np.ndarray, rate: int) -> dict[str, Any]:
        if waveform.size == 0:
            rms = peak = zero_crossing_rate = 0.0
        else:
            rms = float(np.sqrt(np.mean(waveform * waveform)))
            peak = float(np.max(np.abs(waveform)))
            signs = np.signbit(waveform)
            zero_crossing_rate = float(np.count_nonzero(signs[1:] != signs[:-1]) / max(1, len(signs) - 1))
        dbfs = -120.0 if rms <= 1e-8 else 20.0 * float(np.log10(rms))
        return {
            "file": str(output),
            "audio_url": f"/audio/{output.name}",
            "sample_rate": rate,
            "duration_s": round(waveform.size / max(1, rate), 3),
            "elapsed_ms": 0,
            "rms": round(rms, 5),
            "peak": round(peak, 5),
            "dbfs": round(dbfs, 2),
            "zero_crossing_rate": round(zero_crossing_rate, 5),
            "engine": "continuous_level_meter",
        }


def day_night_status() -> dict[str, Any]:
    status: dict[str, Any] = {
        "state": "unknown",
        "source": "unavailable",
        "updated_at": time.time(),
    }
    try:
        for line in reversed(DAY_NIGHT_LOG.read_text(encoding="utf-8", errors="replace").splitlines()):
            value = line.strip()
            if value in {"day", "night"}:
                status.update({"state": value, "source": "day_night_sync_log"})
                return status
    except OSError as exc:
        status["error"] = str(exc)

    try:
        value = IRCUT_MODE_FILE.read_text(encoding="utf-8").strip()
        if value in {"day", "night", "off"}:
            status.update({"state": value, "source": "ircut_saved_mode"})
            return status
    except OSError as exc:
        status["error"] = str(exc)

    if DAY_NIGHT_SCRIPT.exists():
        try:
            completed = subprocess.run(
                ["python3", str(DAY_NIGHT_SCRIPT), "status"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=1.0,
            )
            value = completed.stdout.strip().splitlines()[-1] if completed.stdout.strip() else ""
            if completed.returncode == 0 and value in {"day", "night"}:
                status.update({"state": value, "source": "gpio35"})
                return status
            if completed.stderr.strip():
                status["error"] = completed.stderr.strip()
        except Exception as exc:
            status["error"] = str(exc)
    return status


def age_seconds(epoch_seconds: Any, now: float | None = None) -> float | None:
    try:
        value = float(epoch_seconds)
    except (TypeError, ValueError):
        return None
    now = time.time() if now is None else now
    age = now - value
    if age < 0:
        return None
    return round(age, 3)


def recent_rate(timestamps: deque[float], now: float | None = None, window_s: float = 5.0) -> float | None:
    now = time.time() if now is None else now
    recent = [value for value in timestamps if now - value <= window_s]
    if len(recent) < 2:
        return None
    span = recent[-1] - recent[0]
    if span <= 0:
        return None
    return round((len(recent) - 1) / span, 2)


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
    parser.add_argument("--enable-yamnet", action="store_true", default=os.environ.get("ENABLE_YAMNET", "1") == "1")
    parser.add_argument("--yamnet-seconds", type=float, default=float(os.environ.get("YAMNET_SECONDS", "2")))
    parser.add_argument("--yamnet-interval", type=float, default=float(os.environ.get("YAMNET_INTERVAL", "0.5")))
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
