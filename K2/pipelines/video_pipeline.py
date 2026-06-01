from __future__ import annotations

import argparse
from typing import Any

from ai.vision_ai import VisionWorker
from devices.camera import CameraPipeline
from utils.paths import HLS_DIR, LOG_DIR


class VideoPipeline:
    def __init__(self, args: argparse.Namespace) -> None:
        self.vision = VisionWorker(
            model_path=args.pose_model,
            enabled=args.enable_pose,
            provider=args.pose_provider,
            provider_fallback=args.pose_provider_fallback,
            low_light_mode=args.low_light_mode,
            low_light_gamma=args.low_light_gamma,
            low_light_clahe_clip=args.low_light_clahe_clip,
            low_light_desaturate=args.low_light_desaturate,
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
        self.start_camera = bool(args.start_camera)

    def start(self) -> None:
        self.vision.start()
        if self.start_camera:
            try:
                self.camera.start()
            except Exception as exc:
                print(f"camera start failed: {exc}", flush=True)

    def stop(self) -> None:
        self.camera.stop()
        self.vision.stop()

    def status(self) -> dict[str, Any]:
        return {
            "camera": self.camera.status(),
            "vision": self.vision.status(),
        }
