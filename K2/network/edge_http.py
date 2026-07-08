from __future__ import annotations

import json
import mimetypes
import time
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qsl, urlparse

from services.state import EdgeState
from utils.preview_snapshot import latest_preview_jpeg
from utils.paths import AUDIO_DIR, HLS_DIR, LULLABY_DIR, STATIC_DIR, TEMPLATES_DIR


mimetypes.add_type("application/vnd.apple.mpegurl", ".m3u8")
mimetypes.add_type("video/mp2t", ".ts")

STATE: EdgeState | None = None


class Handler(SimpleHTTPRequestHandler):
    server_version = "DeepZZZK2/0.2"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.log_date_time_string()} {self.address_string()} {fmt % args}", flush=True)

    def end_headers(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path.endswith((".m3u8", ".ts")) or parsed.path.startswith("/static/"):
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
        elif path.startswith("/lullabies/"):
            self._send_file(LULLABY_DIR / path.removeprefix("/lullabies/"))
        elif path == "/api/audio/live.pcm":
            self._stream_live_pcm()
        elif path == "/api/healthz":
            self._send_json(self._state().healthz())
        elif path == "/api/status":
            self._send_json(self._state().status())
        elif path == "/api/detections":
            self._send_json({"ok": True, "result": self._state().vision.latest_result()})
        elif path == "/api/vision/safety":
            self._send_json({"ok": True, "result": self._state().vision_safety()})
        elif path == "/api/activity-zone":
            self._send_json({"ok": True, "result": self._state().activity_zone()})
        elif path == "/api/alerts":
            self._send_json(self._state().alerts())
        elif path == "/api/lullabies":
            self._send_json({"ok": True, "result": self._state().lullaby_status()})
        elif path == "/api/light":
            self._send_json({"ok": True, "light": self._state().hardware.light.status()})
        elif path in {"/api/temperature-humidity", "/api/temperature_humidity"}:
            self._send_json({"ok": True, "temperature_humidity": self._state().hardware.temperature_humidity.status()})
        elif path == "/api/snapshot":
            jpg = self._state().vision.latest_jpeg()
            if jpg is None:
                self._send_json({"ok": False, "error": "no analysis frame yet"}, status=HTTPStatus.NOT_FOUND)
            else:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(jpg)))
                self.end_headers()
                self.wfile.write(jpg)
        elif path == "/api/preview-snapshot":
            jpg = latest_preview_jpeg()
            if jpg is None:
                self._send_json({"ok": False, "error": "no preview frame yet"}, status=HTTPStatus.NOT_FOUND)
            else:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(jpg)))
                self.end_headers()
                self.wfile.write(jpg)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_HEAD(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/":
            self._send_file(TEMPLATES_DIR / "index.html", "text/html; charset=utf-8", head_only=True)
        elif path.startswith("/static/"):
            self._send_file(STATIC_DIR / path.removeprefix("/static/"), head_only=True)
        elif path.startswith("/hls/"):
            self._send_file(HLS_DIR / path.removeprefix("/hls/"), head_only=True)
        elif path.startswith("/audio/"):
            self._send_file(AUDIO_DIR / path.removeprefix("/audio/"), head_only=True)
        elif path.startswith("/lullabies/"):
            self._send_file(LULLABY_DIR / path.removeprefix("/lullabies/"), head_only=True)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        raw_body = self._read_body()
        body = {} if path == "/api/audio/playback/upload" else self._parse_json(raw_body)
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
                self._send_json({"ok": True, "result": state.record_audio(seconds)})
            elif path == "/api/audio/yamnet":
                seconds = float(body.get("seconds", 2.0))
                self._send_json({"ok": True, "result": state.analyze_cry(seconds)})
            elif path == "/api/audio/playback":
                seconds = float(body.get("seconds", 2.0))
                output_device = body.get("output_device")
                result = state.play_recent_audio(
                    seconds,
                    output_device=str(output_device) if output_device not in (None, "") else None,
                    volume=body.get("volume"),
                )
                self._send_json({"ok": True, "result": result})
            elif path == "/api/audio/playback/capture/start":
                self._send_json({"ok": True, "result": state.start_playback_capture()})
            elif path == "/api/audio/playback/capture/stop":
                self._send_json({"ok": True, "result": state.stop_playback_capture(volume=body.get("volume"))})
            elif path == "/api/audio/playback/play":
                output_device = body.get("output_device")
                result = state.play_captured_audio(
                    output_device=str(output_device) if output_device not in (None, "") else None,
                    volume=body.get("volume"),
                )
                self._send_json({"ok": True, "result": result})
            elif path == "/api/audio/playback/stop":
                self._send_json({"ok": True, "result": state.stop_playback()})
            elif path == "/api/audio/playback/volume":
                self._send_json({"ok": True, "result": state.set_playback_volume(body.get("volume"))})
            elif path == "/api/audio/playback/upload":
                query = dict(parse_qsl(parsed.query, keep_blank_values=True))
                loop = str(query.get("loop") or "").lower() in {"1", "true", "yes", "on"}
                result = state.play_uploaded_audio(raw_body, volume=query.get("volume"), loop=loop)
                self._send_json({"ok": True, "result": result})
            elif path == "/api/lullabies/upload":
                query = dict(parse_qsl(parsed.query, keep_blank_values=True))
                title = str(query.get("title") or "") or None
                result = state.save_lullaby(raw_body, title=title, volume=query.get("volume"))
                self._send_json({"ok": True, "result": result})
            elif path == "/api/lullabies/play":
                result = state.play_lullaby(track_id=body.get("track_id"), volume=body.get("volume"))
                self._send_json({"ok": True, "result": result})
            elif path == "/api/audio/talk/start":
                self._send_json({"ok": True, "result": state.start_talk_input()})
            elif path == "/api/audio/talk/chunk":
                result = state.receive_talk_pcm(raw_body)
                self._send_json({"ok": True, "result": result})
            elif path == "/api/audio/talk/stop":
                self._send_json({"ok": True, "result": state.stop_talk_input()})
            elif path == "/api/light":
                duty = float(body.get("duty", 0.0))
                self._send_json({"ok": True, "light": state.set_light_duty(duty)})
            elif path == "/api/activity-zone":
                self._send_json({"ok": True, "result": state.set_activity_zone(body)})
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=500)

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/api/activity-zone":
            self._send_json({"ok": True, "result": self._state().clear_activity_zone()})
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def _state(self) -> EdgeState:
        if STATE is None:
            raise RuntimeError("server state is not initialized")
        return STATE

    def _read_json(self) -> dict[str, Any]:
        return self._parse_json(self._read_body())

    def _parse_json(self, raw: bytes) -> dict[str, Any]:
        if not raw:
            return {}
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return {}
        return payload if isinstance(payload, dict) else {}

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _send_json(self, payload: dict[str, Any], status: int = 200) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _stream_live_pcm(self) -> None:
        state = self._state()
        state.ensure_live_audio_capture()
        position = state.live_audio_position()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Audio-Sample-Rate", "16000")
        self.send_header("X-Audio-Channels", "1")
        self.send_header("X-Audio-Encoding", "pcm_s16le")
        self.end_headers()
        while True:
            try:
                data, position = state.read_live_pcm_since(position, max_bytes=3200, timeout=1.0)
                if not data:
                    continue
                self.wfile.write(data)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception as exc:
                print(f"live pcm stream stopped: {exc}", flush=True)
                time.sleep(0.2)
                return

    def _send_file(self, path: Path, content_type: str | None = None, head_only: bool = False) -> None:
        resolved = path.resolve()
        allowed_roots = [
            STATIC_DIR.resolve(),
            TEMPLATES_DIR.resolve(),
            HLS_DIR.resolve(),
            AUDIO_DIR.resolve(),
        ]
        if not any(resolved == root or root in resolved.parents for root in allowed_roots):
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
        if not head_only:
            self.wfile.write(data)


def serve(host: str, port: int, state: EdgeState) -> None:
    global STATE
    STATE = state
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"DeepZZZ K2 edge service listening on {host}:{port}", flush=True)
    server.serve_forever()
