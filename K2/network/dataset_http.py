from __future__ import annotations

import json
import mimetypes
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from services.state import EdgeState
from utils.paths import DATASET_DIR, STATIC_DIR, TEMPLATES_DIR


CAPTURE_STATE: EdgeState | None = None


class DatasetHandler(SimpleHTTPRequestHandler):
    server_version = "DeepZZZK2Dataset/0.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.log_date_time_string()} dataset {self.address_string()} {fmt % args}", flush=True)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/":
            self._send_file(TEMPLATES_DIR / "dataset.html", "text/html; charset=utf-8")
        elif path.startswith("/static/"):
            self._send_file(STATIC_DIR / path.removeprefix("/static/"))
        elif path.startswith("/dataset/"):
            self._send_file(DATASET_DIR / path.removeprefix("/dataset/"))
        elif path == "/api/dataset/stats":
            self._send_json({"ok": True, "result": self._state().dataset_stats()})
        elif path == "/api/snapshot":
            jpg = self._state().vision.latest_jpeg()
            if jpg is None:
                self.send_error(HTTPStatus.NOT_FOUND, "no analysis frame yet")
            else:
                self._send_jpeg(jpg)
        elif path == "/api/raw-snapshot":
            jpg = self._state().vision.latest_raw_jpeg()
            if jpg is None:
                self.send_error(HTTPStatus.NOT_FOUND, "no raw analysis frame yet")
            else:
                self._send_jpeg(jpg)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        body = self._read_json()
        path = urlparse(self.path).path
        state = self._state()
        try:
            if path == "/api/dataset/capture":
                self._send_json({"ok": True, "result": state.capture_dataset_sample(body.get("labels"))})
            elif path == "/api/dataset/confirm":
                self._send_json({"ok": True, "result": state.confirm_dataset_sample(str(body.get("id", "")))})
            elif path == "/api/dataset/cancel":
                self._send_json({"ok": True, "result": state.delete_dataset_sample(str(body.get("id", "")))})
            elif path == "/api/dataset/stats":
                self._send_json({"ok": True, "result": state.dataset_stats()})
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except Exception as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=500)

    def _state(self) -> EdgeState:
        if CAPTURE_STATE is None:
            raise RuntimeError("dataset capture state is not initialized")
        if not CAPTURE_STATE.args.enable_dataset_capture:
            raise PermissionError("dataset capture is disabled")
        return CAPTURE_STATE

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
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

    def _send_jpeg(self, jpg: bytes) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(jpg)))
        self.end_headers()
        self.wfile.write(jpg)

    def _send_file(self, path: Path, content_type: str | None = None) -> None:
        resolved = path.resolve()
        allowed_roots = [STATIC_DIR.resolve(), TEMPLATES_DIR.resolve(), DATASET_DIR.resolve()]
        if not any(str(resolved).startswith(str(root)) for root in allowed_roots):
            self.send_error(HTTPStatus.FORBIDDEN)
            return
        if not resolved.exists() or not resolved.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        data = resolved.read_bytes()
        content_type = content_type or mimetypes.guess_type(str(resolved))[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def serve_dataset(host: str, port: int, state: EdgeState) -> None:
    global CAPTURE_STATE
    CAPTURE_STATE = state
    server = ThreadingHTTPServer((host, port), DatasetHandler)
    print(f"DeepZZZ K2 dataset capture listening on {host}:{port}", flush=True)
    server.serve_forever()
