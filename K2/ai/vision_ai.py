from __future__ import annotations

from collections import deque
import threading
import time
from typing import Any

import cv2
import numpy as np

from ai.low_light import enhance_low_light, should_enhance_low_light
from ai.runtime import CPU_PROVIDER, provider_label
from ai.pose import YoloPoseEngine
from devices.day_night_sensor import day_night_status


PENDING_FRAME_LIMIT = 8


class VisionWorker:
    def __init__(
        self,
        model_path: str | None = None,
        enabled: bool = False,
        provider: str = CPU_PROVIDER,
        provider_fallback: bool = True,
        low_light_mode: str = "off",
        low_light_gamma: float = 1.35,
        low_light_clahe_clip: float = 2.0,
        low_light_desaturate: bool = True,
    ) -> None:
        self._lock = threading.Lock()
        self._event = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._pending: deque[tuple[np.ndarray, dict[str, Any]]] = deque(maxlen=PENDING_FRAME_LIMIT)
        self._latest: dict[str, Any] | None = None
        self._latest_raw_jpeg: bytes | None = None
        self._latest_jpeg: bytes | None = None
        self._running = False
        self._submitted = 0
        self._overwritten = 0
        self._processed = 0
        self._processed_at: deque[float] = deque(maxlen=120)
        self._profile: dict[str, Any] | None = None
        self._profile_history: deque[dict[str, float]] = deque(maxlen=120)
        self._model_path = model_path
        self._model_enabled = enabled
        self._provider = provider
        self._provider_fallback = provider_fallback
        self._low_light_mode = low_light_mode
        self._low_light_gamma = low_light_gamma
        self._low_light_clahe_clip = low_light_clahe_clip
        self._low_light_desaturate = low_light_desaturate
        self._engine: YoloPoseEngine | None = None
        self._error: str | None = None
        self._active_provider: str | None = None
        self._active_providers: list[str] = []

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
            if len(self._pending) >= PENDING_FRAME_LIMIT:
                self._overwritten += 1
            meta = {**meta, "submitted_at": time.time()}
            self._pending.append((frame.copy(), meta))
            self._submitted += 1
            self._event.set()

    def latest_result(self) -> dict[str, Any] | None:
        with self._lock:
            return self._latest

    def latest_jpeg(self) -> bytes | None:
        with self._lock:
            return self._latest_jpeg

    def latest_raw_jpeg(self) -> bytes | None:
        with self._lock:
            return self._latest_raw_jpeg

    def status(self) -> dict[str, Any]:
        with self._lock:
            now = time.time()
            return {
                "running": self._running,
                "engine": self._engine_name(),
                "processed": self._processed,
                "submitted": self._submitted,
                "overwritten": self._overwritten,
                "pending": len(self._pending),
                "pending_limit": PENDING_FRAME_LIMIT,
                "actual_fps": recent_rate(self._processed_at, now=now),
                "latest": self._latest,
                "profile": self._profile,
                "profile_stats": summarize_profiles(self._profile_history),
                "model_enabled": self._model_enabled,
                "model_path": self._model_path,
                "provider": self._active_provider or self._provider,
                "requested_provider": self._provider,
                "providers": self._active_providers,
                "provider_fallback": self._provider_fallback,
                "low_light": {
                    "mode": self._low_light_mode,
                    "gamma": self._low_light_gamma,
                    "clahe_clip": self._low_light_clahe_clip,
                    "desaturate": self._low_light_desaturate,
                },
                "error": self._error,
            }

    def _run(self) -> None:
        while not self._stop.is_set():
            with self._lock:
                packet = self._pending.popleft() if self._pending else None
            if packet is None:
                self._event.wait(timeout=0.5)
                self._event.clear()
                continue
            frame, meta = packet
            loop_started = time.perf_counter()
            submitted_at = meta.get("submitted_at")
            queued_ms = None
            if submitted_at is not None:
                queued_ms = max(0.0, (time.time() - float(submitted_at)) * 1000.0)
            if self._model_enabled:
                infer_started = time.perf_counter()
                result, preview = self._infer(frame, meta)
                infer_ms = (time.perf_counter() - infer_started) * 1000.0
            else:
                infer_started = time.perf_counter()
                result, preview = self._sample(frame, meta)
                infer_ms = (time.perf_counter() - infer_started) * 1000.0
            raw_jpeg_started = time.perf_counter()
            raw_ok, raw_encoded = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 95])
            raw_jpeg_ms = (time.perf_counter() - raw_jpeg_started) * 1000.0
            preview_jpeg_started = time.perf_counter()
            ok, encoded = cv2.imencode(".jpg", preview, [cv2.IMWRITE_JPEG_QUALITY, 78])
            preview_jpeg_ms = (time.perf_counter() - preview_jpeg_started) * 1000.0
            lock_started = time.perf_counter()
            with self._lock:
                lock_ms = (time.perf_counter() - lock_started) * 1000.0
                total_ms = (time.perf_counter() - loop_started) * 1000.0
                profile = {
                    "queued_ms": None if queued_ms is None else round(queued_ms, 2),
                    "infer_ms": round(infer_ms, 2),
                    "model_elapsed_ms": result.get("elapsed_ms"),
                    "raw_jpeg_ms": round(raw_jpeg_ms, 2),
                    "preview_jpeg_ms": round(preview_jpeg_ms, 2),
                    "lock_ms": round(lock_ms, 2),
                    "total_ms": round(total_ms, 2),
                    "pending_after_process": bool(self._pending),
                    "pending_count_after_process": len(self._pending),
                }
                self._processed_at.append(time.time())
                self._processed += 1
                self._latest = result
                self._latest_raw_jpeg = raw_encoded.tobytes() if raw_ok else None
                self._latest_jpeg = encoded.tobytes() if ok else None
                self._profile = profile
                self._profile_history.append(
                    {
                        key: float(value)
                        for key, value in profile.items()
                        if isinstance(value, (int, float)) and value is not None
                    }
                )

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
            self._engine = YoloPoseEngine(
                self._model_path,
                provider=self._provider,
                provider_fallback=self._provider_fallback,
            )
            self._active_provider = self._engine.provider
            self._active_providers = self._engine.session.get_providers()
        try:
            day_night = day_night_status()
            day_night_state = str(day_night.get("state") or "unknown")
            enhance, brightness, low_light_mode = should_enhance_low_light(
                frame,
                self._low_light_mode,
                day_night_state,
            )
            inference_frame = (
                enhance_low_light(
                    frame,
                    gamma=self._low_light_gamma,
                    clahe_clip_limit=self._low_light_clahe_clip,
                    desaturate=self._low_light_desaturate,
                )
                if enhance
                else frame
            )
            result, annotated = self._engine.infer(inference_frame)
            result["captured_at"] = meta.get("captured_at")
            result["low_light"] = {
                "mode": low_light_mode,
                "active": enhance,
                "brightness": round(brightness, 2),
                "day_night": day_night_state,
                "gamma": self._low_light_gamma,
                "clahe_clip": self._low_light_clahe_clip,
                "desaturate": self._low_light_desaturate,
            }
            self._active_provider = result.get("provider")
            self._active_providers = result.get("providers") or self._active_providers
            self._error = None
            return result, annotated
        except Exception as exc:
            self._error = str(exc)
            result, preview = self._sample(frame, meta)
            result["error"] = str(exc)
            return result, preview

    def _engine_name(self) -> str:
        if not self._model_enabled:
            return "lowres_sampler"
        provider = self._active_provider or self._provider
        return f"yolov8n_pose_{provider_label(provider)}"


def recent_rate(timestamps: deque[float], now: float | None = None, window_s: float = 5.0) -> float | None:
    now = time.time() if now is None else now
    recent = [value for value in timestamps if now - value <= window_s]
    if len(recent) < 2:
        return None
    span = recent[-1] - recent[0]
    if span <= 0:
        return None
    return round((len(recent) - 1) / span, 2)


def summarize_profiles(profiles: deque[dict[str, float]]) -> dict[str, dict[str, float | None]]:
    keys = ("queued_ms", "infer_ms", "model_elapsed_ms", "raw_jpeg_ms", "preview_jpeg_ms", "lock_ms", "total_ms")
    return {key: summarize_profile_values([profile[key] for profile in profiles if key in profile]) for key in keys}


def summarize_profile_values(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"avg": None, "p95": None, "max": None}
    sorted_values = sorted(values)
    p95_index = min(len(sorted_values) - 1, max(0, round((len(sorted_values) - 1) * 0.95)))
    return {
        "avg": round(sum(values) / len(values), 2),
        "p95": round(sorted_values[p95_index], 2),
        "max": round(max(values), 2),
    }
