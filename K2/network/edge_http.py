from __future__ import annotations

import json
import mimetypes
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from services.state import EdgeState
from utils.paths import AUDIO_DIR, HLS_DIR, STATIC_DIR, TEMPLATES_DIR


mimetypes.add_type("application/vnd.apple.mpegurl", ".m3u8")
mimetypes.add_type("video/mp2t", ".ts")

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
        allowed_roots = [
            STATIC_DIR.resolve(),
            TEMPLATES_DIR.resolve(),
            HLS_DIR.resolve(),
            AUDIO_DIR.resolve(),
        ]
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


def serve(host: str, port: int, state: EdgeState) -> None:
    global STATE
    STATE = state
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"DeepZZZ K2 edge service listening on {host}:{port}", flush=True)
    server.serve_forever()
