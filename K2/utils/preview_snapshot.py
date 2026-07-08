from __future__ import annotations

import subprocess
import time

from utils.paths import HLS_DIR


def latest_preview_jpeg(max_age_s: float = 5.0) -> bytes | None:
    segment = latest_hls_segment(max_age_s=max_age_s)
    if segment is None:
        return None
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(segment),
        "-frames:v",
        "1",
        "-q:v",
        "3",
        "-f",
        "image2pipe",
        "-vcodec",
        "mjpeg",
        "pipe:1",
    ]
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3)
    if completed.returncode != 0 or not completed.stdout:
        return None
    return completed.stdout


def latest_hls_segment(max_age_s: float):
    if not HLS_DIR.exists():
        return None
    segments = [item for item in HLS_DIR.glob("*.ts") if item.is_file()]
    if not segments:
        return None
    latest = max(segments, key=lambda item: item.stat().st_mtime)
    if time.time() - latest.stat().st_mtime > max_age_s:
        return None
    return latest
