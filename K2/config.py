from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any, Callable, TypeVar

from utils.paths import PROJECT_ROOT


T = TypeVar("T")

CONFIG_PATH = PROJECT_ROOT / "config.json"

DEFAULTS: dict[str, Any] = {
    "host": "0.0.0.0",
    "port": 7860,
    "dataset_capture_port": 7861,
    "camera_device": "auto",
    "preview_size": "1280x720",
    "preview_fps": 30,
    "analysis_width": 320,
    "analysis_fps": 0.0,
    "video_decoder": "auto",
    "hls_time": 1.0,
    "hls_list_size": 5,
    "audio_device": "plughw:0,0",
    "audio_rate": 16000,
    "yamnet_model": "models/yamnet/yamnet.onnx",
    "yamnet_labels": "models/yamnet/yamnet_class_map.csv",
    "enable_yamnet": True,
    "yamnet_seconds": 2.0,
    "yamnet_interval": 0.5,
    "pose_model": "/home/z/.brdk_models/pose/yolov8n-pose-320.calib20260601.q.onnx",
    "pose_provider": "SpaceMITExecutionProvider",
    "pose_provider_fallback": False,
    "low_light_mode": "off",
    "low_light_gamma": 1.35,
    "low_light_clahe_clip": 2.0,
    "low_light_desaturate": False,
    "enable_dataset_capture": False,
    "enable_pose": False,
    "start_camera": True,
}

ENV_KEYS = {
    "host": "HOST",
    "port": "PORT",
    "dataset_capture_port": "DATASET_CAPTURE_PORT",
    "camera_device": "CAMERA_DEVICE",
    "preview_size": "PREVIEW_SIZE",
    "preview_fps": "PREVIEW_FPS",
    "analysis_width": "ANALYSIS_WIDTH",
    "analysis_fps": "ANALYSIS_FPS",
    "video_decoder": "VIDEO_DECODER",
    "hls_time": "HLS_TIME",
    "hls_list_size": "HLS_LIST_SIZE",
    "audio_device": "AUDIO_DEVICE",
    "audio_rate": "AUDIO_RATE",
    "yamnet_model": "YAMNET_MODEL",
    "yamnet_labels": "YAMNET_LABELS",
    "enable_yamnet": "ENABLE_YAMNET",
    "yamnet_seconds": "YAMNET_SECONDS",
    "yamnet_interval": "YAMNET_INTERVAL",
    "pose_model": "POSE_MODEL",
    "pose_provider": "POSE_PROVIDER",
    "pose_provider_fallback": "POSE_PROVIDER_FALLBACK",
    "low_light_mode": "LOW_LIGHT_MODE",
    "low_light_gamma": "LOW_LIGHT_GAMMA",
    "low_light_clahe_clip": "LOW_LIGHT_CLAHE_CLIP",
    "low_light_desaturate": "LOW_LIGHT_DESATURATE",
    "enable_dataset_capture": "ENABLE_DATASET_CAPTURE",
    "enable_pose": "ENABLE_POSE",
}


def parse_args() -> argparse.Namespace:
    values = resolved_defaults(load_config(CONFIG_PATH))
    parser = argparse.ArgumentParser(description="DeepZZZ K2 edge service")
    parser.add_argument("--host", default=values["host"])
    parser.add_argument("--port", type=int, default=values["port"])
    parser.add_argument("--dataset-capture-port", type=int, default=values["dataset_capture_port"])
    parser.add_argument("--device", default=values["camera_device"])
    parser.add_argument("--preview-size", default=values["preview_size"])
    parser.add_argument("--preview-fps", type=int, default=values["preview_fps"])
    parser.add_argument("--analysis-width", type=int, default=values["analysis_width"])
    parser.add_argument("--analysis-fps", type=float, default=values["analysis_fps"])
    parser.add_argument("--video-decoder", default=values["video_decoder"])
    parser.add_argument("--hls-time", type=float, default=values["hls_time"])
    parser.add_argument("--hls-list-size", type=int, default=values["hls_list_size"])
    parser.add_argument("--audio-device", default=values["audio_device"])
    parser.add_argument("--audio-rate", type=int, default=values["audio_rate"])
    parser.add_argument("--yamnet-model", default=values["yamnet_model"])
    parser.add_argument("--yamnet-labels", default=values["yamnet_labels"])
    parser.add_argument("--enable-yamnet", dest="enable_yamnet", action="store_true", default=values["enable_yamnet"])
    parser.add_argument("--disable-yamnet", dest="enable_yamnet", action="store_false")
    parser.add_argument("--yamnet-seconds", type=float, default=values["yamnet_seconds"])
    parser.add_argument("--yamnet-interval", type=float, default=values["yamnet_interval"])
    parser.add_argument("--pose-model", default=values["pose_model"])
    parser.add_argument("--pose-provider", default=values["pose_provider"])
    parser.add_argument(
        "--pose-provider-fallback",
        dest="pose_provider_fallback",
        action="store_true",
        default=values["pose_provider_fallback"],
    )
    parser.add_argument("--no-pose-provider-fallback", dest="pose_provider_fallback", action="store_false")
    parser.add_argument("--low-light-mode", choices=["off", "auto", "night", "on"], default=values["low_light_mode"])
    parser.add_argument("--low-light-gamma", type=float, default=values["low_light_gamma"])
    parser.add_argument("--low-light-clahe-clip", type=float, default=values["low_light_clahe_clip"])
    parser.add_argument("--low-light-desaturate", dest="low_light_desaturate", action="store_true", default=values["low_light_desaturate"])
    parser.add_argument("--no-low-light-desaturate", dest="low_light_desaturate", action="store_false")
    parser.add_argument("--enable-dataset-capture", dest="enable_dataset_capture", action="store_true", default=values["enable_dataset_capture"])
    parser.add_argument("--disable-dataset-capture", dest="enable_dataset_capture", action="store_false")
    parser.add_argument("--enable-pose", dest="enable_pose", action="store_true", default=values["enable_pose"])
    parser.add_argument("--disable-pose", dest="enable_pose", action="store_false")
    parser.add_argument("--no-camera", dest="start_camera", action="store_false", default=values["start_camera"])
    parser.add_argument("--camera", dest="start_camera", action="store_true")
    return parser.parse_args()


def load_config(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid config file {path}: {exc}") from exc


def resolved_defaults(file_config: dict[str, Any]) -> dict[str, Any]:
    values = DEFAULTS.copy()
    values.update({key: value for key, value in file_config.items() if key in DEFAULTS})

    for key, env_key in ENV_KEYS.items():
        if env_key not in os.environ:
            continue
        default = DEFAULTS[key]
        values[key] = cast_value(os.environ[env_key], type(default))
    return values


def cast_value(value: Any, target: Callable[[Any], T]) -> T:
    if target is bool:
        return parse_bool(value)  # type: ignore[return-value]
    return target(value)


def parse_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}
