from __future__ import annotations

import threading
import time
from typing import Any

import cv2
import numpy as np

from .yolo_pose import YoloPoseEngine


class VisionWorker:
    def __init__(
        self,
        model_path: str | None = None,
        enabled: bool = False,
        provider: str = "CPUExecutionProvider",
    ) -> None:
        self._lock = threading.Lock()
        self._event = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._pending: tuple[np.ndarray, dict[str, Any]] | None = None
        self._latest: dict[str, Any] | None = None
        self._latest_jpeg: bytes | None = None
        self._running = False
        self._processed = 0
        self._model_path = model_path
        self._model_enabled = enabled
        self._provider = provider
        self._engine: YoloPoseEngine | None = None
        self._error: str | None = None

    def start(self) -> None:
        with self._lock:
            if self._running:
                return
            self._stop.clear()
            self._running = True
            self._thread = threading.Thread(target=self._run, daemon=True)
            self._thread.start()

    def stop(self) -> None:
        with self._lock:
            self._running = False
            self._stop.set()
            self._event.set()
            thread = self._thread
            self._thread = None
        if thread is not None and thread.is_alive():
            thread.join(timeout=2)

    def submit_frame(self, frame: np.ndarray, meta: dict[str, Any]) -> None:
        with self._lock:
            self._pending = (frame.copy(), meta)
            self._event.set()

    def latest_result(self) -> dict[str, Any] | None:
        with self._lock:
            return self._latest

    def latest_jpeg(self) -> bytes | None:
        with self._lock:
            return self._latest_jpeg

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {
                "running": self._running,
                "engine": "yolov8n_pose_cpu" if self._model_enabled else "lowres_sampler",
                "processed": self._processed,
                "latest": self._latest,
                "model_enabled": self._model_enabled,
                "model_path": self._model_path,
                "provider": self._provider,
                "error": self._error,
            }

    def _run(self) -> None:
        while not self._stop.is_set():
            self._event.wait(timeout=0.5)
            self._event.clear()
            with self._lock:
                packet = self._pending
                self._pending = None
            if packet is None:
                continue
            frame, meta = packet
            if self._model_enabled:
                result, preview = self._infer(frame, meta)
            else:
                result, preview = self._sample(frame, meta)
            ok, encoded = cv2.imencode(".jpg", preview, [cv2.IMWRITE_JPEG_QUALITY, 78])
            with self._lock:
                self._processed += 1
                self._latest = result
                self._latest_jpeg = encoded.tobytes() if ok else None

    def _sample(self, frame: np.ndarray, meta: dict[str, Any]) -> tuple[dict[str, Any], np.ndarray]:
        started = time.perf_counter()
        preview = frame.copy()
        text = f"sample {frame.shape[1]}x{frame.shape[0]} @ low fps  no model"
        cv2.rectangle(preview, (0, 0), (preview.shape[1], 30), (20, 24, 28), -1)
        cv2.putText(preview, text, (8, 21), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (245, 245, 240), 1, cv2.LINE_AA)

        result = {
            "engine": "lowres_sampler",
            "frame_size": [int(frame.shape[1]), int(frame.shape[0])],
            "captured_at": meta.get("captured_at"),
            "updated_at": time.time(),
            "elapsed_ms": round((time.perf_counter() - started) * 1000, 2),
            "model_active": False,
        }
        return result, preview

    def _infer(self, frame: np.ndarray, meta: dict[str, Any]) -> tuple[dict[str, Any], np.ndarray]:
        if self._engine is None:
            if not self._model_path:
                raise RuntimeError("pose model is enabled but model path is empty")
            self._engine = YoloPoseEngine(self._model_path, provider=self._provider)
        try:
            result, annotated = self._engine.infer(frame)
            result["captured_at"] = meta.get("captured_at")
            self._error = None
            return result, annotated
        except Exception as exc:
            self._error = str(exc)
            result, preview = self._sample(frame, meta)
            result["error"] = str(exc)
            return result, preview
