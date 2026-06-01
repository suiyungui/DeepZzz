from __future__ import annotations

import argparse
import time
from typing import Any

from devices.day_night_sensor import day_night_status
from pipelines.audio_pipeline import AudioPipeline
from pipelines.video_pipeline import VideoPipeline
from services.dataset_capture import DatasetCaptureService
from services.resources import ResourceMonitor
from utils.metrics import age_seconds


class EdgeState:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.video = VideoPipeline(args)
        self.audio = AudioPipeline(args)
        self.resources = ResourceMonitor()
        self.dataset = DatasetCaptureService(enabled=bool(args.enable_dataset_capture))
        self.started_at = time.time()

    @property
    def camera(self):
        return self.video.camera

    @property
    def vision(self):
        return self.video.vision

    @property
    def latest_audio(self) -> dict[str, Any] | None:
        return self.audio.latest_audio

    @property
    def latest_yamnet(self) -> dict[str, Any] | None:
        return self.audio.latest_yamnet

    def start(self) -> None:
        self.video.start()
        self.audio.start()

    def stop(self) -> None:
        self.audio.stop()
        self.video.stop()

    def status(self) -> dict[str, Any]:
        now = time.time()
        video_status = self.video.status()
        vision = video_status["vision"]
        camera = video_status["camera"]
        resources = self.resources.snapshot()
        yamnet = self.audio.latest_yamnet
        return {
            "ok": True,
            "uptime_s": round(now - self.started_at, 1),
            "camera": camera,
            "vision": vision,
            "resources": resources,
            "audio": self.audio.latest_audio,
            "yamnet": yamnet,
            "day_night": day_night_status(),
            "dataset": self.dataset.status(),
            "summary": self._summary(vision, yamnet, now),
            "config": self._config(),
        }

    def record_audio(self, seconds: float) -> dict[str, Any]:
        return self.audio.record_audio(seconds)

    def analyze_cry(self, seconds: float) -> dict[str, Any]:
        return self.audio.analyze_cry(seconds)

    def capture_dataset_sample(self, labels: dict[str, Any] | None = None) -> dict[str, Any]:
        now = time.time()
        day_night = day_night_status()
        labels = normalize_dataset_labels(labels, day_night)
        metadata = {
            "created_at": now,
            "labels": labels,
            "day_night": day_night,
            "camera": self.camera.status(),
            "vision": self.vision.latest_result(),
            "config": self._config(),
        }
        return self.dataset.capture_pending(self.vision.latest_raw_jpeg(), metadata)

    def delete_dataset_sample(self, sample_id: str) -> dict[str, Any]:
        return self.dataset.cancel(sample_id)

    def confirm_dataset_sample(self, sample_id: str) -> dict[str, Any]:
        return self.dataset.confirm(sample_id)

    def dataset_stats(self) -> dict[str, Any]:
        return {**self.dataset.status(), "day_night": day_night_status()}

    def _summary(self, vision: dict[str, Any], yamnet: dict[str, Any] | None, now: float) -> dict[str, Any]:
        latest = vision.get("latest") or {}
        video_updated_at = latest.get("updated_at")
        audio_updated_at = yamnet.get("updated_at") if yamnet else None
        return {
            "video_age_s": age_seconds(video_updated_at, now),
            "video_fps": vision.get("actual_fps"),
            "audio_age_s": age_seconds(audio_updated_at, now),
            "audio_rate_hz": self.audio.audio_rate_hz(now=now),
            "npu_percent": estimated_npu_percent(vision),
        }

    def _config(self) -> dict[str, Any]:
        return {
            "preview_size": self.args.preview_size,
            "preview_fps": self.args.preview_fps,
            "analysis_width": self.args.analysis_width,
            "analysis_fps": self.args.analysis_fps,
            "video_decoder": self.args.video_decoder,
            "pose_model": self.args.pose_model,
            "enable_pose": self.args.enable_pose,
            "pose_provider": self.args.pose_provider,
            "pose_provider_fallback": self.args.pose_provider_fallback,
            "low_light_mode": self.args.low_light_mode,
            "low_light_gamma": self.args.low_light_gamma,
            "low_light_clahe_clip": self.args.low_light_clahe_clip,
            "low_light_desaturate": self.args.low_light_desaturate,
            "enable_dataset_capture": self.args.enable_dataset_capture,
            "dataset_capture_port": self.args.dataset_capture_port,
            "audio_device": self.args.audio_device,
            "yamnet_model": self.args.yamnet_model,
            "enable_yamnet": self.args.enable_yamnet,
            "yamnet_seconds": self.args.yamnet_seconds,
            "yamnet_interval": self.args.yamnet_interval,
        }


def estimated_npu_percent(vision: dict[str, Any]) -> float | None:
    provider = str(vision.get("provider") or "")
    if provider != "SpaceMITExecutionProvider":
        return None
    latest = vision.get("latest") or {}
    elapsed_ms = latest.get("elapsed_ms")
    fps = vision.get("actual_fps")
    try:
        busy = float(elapsed_ms) * float(fps) / 10.0
    except (TypeError, ValueError):
        return None
    return round(max(0.0, min(100.0, busy)), 1)


def normalize_dataset_labels(labels: dict[str, Any] | None, day_night: dict[str, Any]) -> dict[str, str]:
    light = str((labels or {}).get("light") or "").strip().lower()
    if light not in {"day", "night_ir", "low_light"}:
        light = "night_ir" if day_night.get("state") == "night" else "day"
    return {"light": light}
