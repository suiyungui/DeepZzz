from __future__ import annotations

import os
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable

import cv2
import numpy as np


FrameCallback = Callable[[np.ndarray, dict], None]


class CameraPipeline:
    def __init__(
        self,
        hls_dir: Path,
        log_dir: Path,
        frame_callback: FrameCallback,
        device: str = "auto",
        preview_size: str = "1280x720",
        preview_fps: int = 30,
        hls_time: float = 1.0,
        hls_list_size: int = 5,
        analysis_width: int = 416,
        analysis_fps: float = 5.0,
        video_decoder: str = "auto",
    ) -> None:
        self.hls_dir = hls_dir
        self.log_dir = log_dir
        self.frame_callback = frame_callback
        self.device = device
        self.preview_size = preview_size
        self.preview_fps = preview_fps
        self.hls_time = hls_time
        self.hls_list_size = hls_list_size
        self.analysis_width = analysis_width
        self.analysis_fps = analysis_fps
        self.video_decoder = video_decoder
        self._lock = threading.RLock()
        self._process: subprocess.Popen | None = None
        self._reader_thread: threading.Thread | None = None
        self._stderr_thread: threading.Thread | None = None
        self._running = False
        self._error: str | None = None
        self._device_path: str | None = None
        self._frames = 0
        self._started_at = 0.0

    def start(self) -> dict:
        with self._lock:
            if self._running:
                return self.status()
            self.hls_dir.mkdir(parents=True, exist_ok=True)
            self.log_dir.mkdir(parents=True, exist_ok=True)
            for item in self.hls_dir.glob("*"):
                if item.is_file():
                    item.unlink()
            try:
                device = find_camera_device(self.device)
                command = self._ffmpeg_command(device)
                process = subprocess.Popen(
                    command,
                    stdout=subprocess.PIPE if self.analysis_fps > 0 else subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    bufsize=0,
                )
            except Exception as exc:
                self._running = False
                self._error = str(exc)
                raise
            self._process = process
            self._running = True
            self._error = None
            self._device_path = device
            self._frames = 0
            self._started_at = time.time()
            self._reader_thread = None
            self._stderr_thread = threading.Thread(target=self._read_stderr, daemon=True)
            if self.analysis_fps > 0:
                self._reader_thread = threading.Thread(target=self._read_mjpeg_pipe, daemon=True)
                self._reader_thread.start()
            self._stderr_thread.start()
            return self.status()

    def stop(self) -> dict:
        with self._lock:
            process = self._process
            self._process = None
            self._running = False
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        return self.status()

    def status(self) -> dict:
        with self._lock:
            alive = self._process is not None and self._process.poll() is None
            return {
                "running": self._running and alive,
                "device": self._device_path,
                "preview_size": self.preview_size,
                "preview_fps": self.preview_fps,
                "analysis_width": self.analysis_width,
                "analysis_fps": self.analysis_fps,
                "video_decoder": self._effective_decoder(),
                "frames": self._frames,
                "uptime_s": round(time.time() - self._started_at, 1) if self._started_at else 0,
                "error": self._error,
                "hls_ready": (self.hls_dir / "stream.m3u8").exists(),
            }

    def _ffmpeg_command(self, device: str) -> list[str]:
        command = [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "warning",
            "-fflags",
            "nobuffer",
            "-flags",
            "low_delay",
            "-f",
            "v4l2",
            "-input_format",
            "h264",
            "-video_size",
            self.preview_size,
            "-framerate",
            str(self.preview_fps),
        ]
        decoder = self._effective_decoder()
        if decoder:
            command.extend(["-c:v", decoder])
        command.extend(
            [
            "-i",
            device,
            "-an",
            "-map",
            "0:v:0",
            "-c:v",
            "copy",
            "-f",
            "hls",
            "-hls_time",
            str(self.hls_time),
            "-hls_list_size",
            str(self.hls_list_size),
            "-hls_delete_threshold",
            "2",
            "-hls_flags",
            "delete_segments+omit_endlist+independent_segments+program_date_time",
            "-hls_segment_filename",
            str(self.hls_dir / "segment_%05d.ts"),
            str(self.hls_dir / "stream.m3u8"),
            ]
        )
        if self.analysis_fps > 0:
            command.extend(
                [
                    "-map",
                    "0:v:0",
                    "-vf",
                    f"fps={self.analysis_fps},scale={self.analysis_width}:-2",
                    "-q:v",
                    "5",
                    "-f",
                    "image2pipe",
                    "-vcodec",
                    "mjpeg",
                    "pipe:1",
                ]
            )
        return command

    def _effective_decoder(self) -> str | None:
        decoder = (self.video_decoder or "auto").strip().lower()
        if decoder in {"", "none", "software", "sw"}:
            return None
        if decoder == "auto":
            return "h264_stcodec" if self.analysis_fps > 0 else None
        return decoder

    def _read_mjpeg_pipe(self) -> None:
        process = self._process
        if process is None or process.stdout is None:
            return
        buffer = bytearray()
        while True:
            chunk = process.stdout.read(4096)
            if not chunk:
                break
            buffer.extend(chunk)
            while True:
                start = buffer.find(b"\xff\xd8")
                end = buffer.find(b"\xff\xd9", start + 2)
                if start < 0 or end < 0:
                    if len(buffer) > 1024 * 1024:
                        del buffer[:-2]
                    break
                jpg = bytes(buffer[start : end + 2])
                del buffer[: end + 2]
                frame = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                meta = {"source": "ffmpeg-lowres", "captured_at": time.time()}
                self.frame_callback(frame, meta)
                with self._lock:
                    self._frames += 1
        with self._lock:
            if self._running:
                self._running = False
                self._error = "ffmpeg analysis pipe ended"

    def _read_stderr(self) -> None:
        process = self._process
        if process is None or process.stderr is None:
            return
        log_path = self.log_dir / "ffmpeg.log"
        with log_path.open("ab") as handle:
            while True:
                chunk = process.stderr.readline()
                if not chunk:
                    break
                handle.write(chunk)
                handle.flush()
                text = chunk.decode("utf-8", errors="replace").strip()
                if text:
                    with self._lock:
                        self._error = text[-500:]


def find_camera_device(device: str) -> str:
    if device and device != "auto":
        if Path(device).exists():
            return device
        raise FileNotFoundError(f"camera device does not exist: {device}")

    candidates: list[Path] = []
    for pattern in (
        "/dev/v4l/by-id/*video-index0",
        "/dev/v4l/by-path/*usb*video-index0",
        "/dev/video*",
    ):
        candidates.extend(sorted(Path("/").glob(pattern.removeprefix("/"))))

    for path in candidates:
        name = str(path)
        if any(token in name.lower() for token in ("usb", "camera", "hdmi")):
            return name

    for path in candidates:
        return str(path)
    raise FileNotFoundError("no camera device found")
