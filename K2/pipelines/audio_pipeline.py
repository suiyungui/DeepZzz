from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path
import threading
import time
from typing import Any

import numpy as np

from ai.audio_ai import ContinuousWaveformRecorder, CryStateTracker, YamnetCryDetector, record_waveform, write_waveform
from devices.mic import record_audio_level
from utils.metrics import recent_rate
from utils.paths import AUDIO_DIR


class AudioPipeline:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.audio_lock = threading.Lock()
        self.yamnet_lock = threading.Lock()
        self.latest_audio: dict[str, Any] | None = None
        self.latest_yamnet: dict[str, Any] | None = None
        self._yamnet: YamnetCryDetector | None = None
        self._audio_recorder: ContinuousWaveformRecorder | None = None
        self._cry_state = CryStateTracker()
        self._yamnet_checks = 0
        self._yamnet_checked_at: deque[float] = deque(maxlen=120)
        self._yamnet_stop = threading.Event()
        self._yamnet_thread: threading.Thread | None = None

    def start(self) -> None:
        if self.args.enable_yamnet:
            self.start_yamnet_loop()

    def stop(self) -> None:
        self.stop_yamnet_loop()

    def start_yamnet_loop(self) -> None:
        if self._yamnet_thread and self._yamnet_thread.is_alive():
            return
        self._yamnet_stop.clear()
        self._yamnet_thread = threading.Thread(target=self._yamnet_loop, name="yamnet-loop", daemon=True)
        self._yamnet_thread.start()

    def stop_yamnet_loop(self) -> None:
        self._yamnet_stop.set()
        if self._yamnet_thread and self._yamnet_thread.is_alive():
            self._yamnet_thread.join(timeout=2)
        if self._audio_recorder is not None:
            self._audio_recorder.stop()

    def audio_rate_hz(self, now: float | None = None) -> float | None:
        return recent_rate(self._yamnet_checked_at, now=now)

    def record_audio(self, seconds: float) -> dict[str, Any]:
        seconds = max(0.5, min(float(seconds), 10.0))
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        output = AUDIO_DIR / f"mic_{int(time.time() * 1000)}.wav"
        if self._audio_recorder and self._audio_recorder.running:
            waveform, rate = self._audio_recorder.latest_window(seconds)
            write_waveform(output, waveform, rate)
            result = self._audio_level_from_waveform(output, waveform, rate)
            self.latest_audio = result
            return result
        with self.audio_lock:
            result = record_audio_level(
                output,
                seconds=seconds,
                device=self.args.audio_device,
                sample_rate=self.args.audio_rate,
            )
            self.latest_audio = result
            return result

    def analyze_cry(self, seconds: float) -> dict[str, Any]:
        seconds = max(1.0, min(float(seconds), 10.0))
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        if self._audio_recorder and self._audio_recorder.running:
            waveform, rate = self._audio_recorder.latest_window(seconds)
            return self._analyze_cry_waveform(waveform, rate, save_audio=True)
        with self.audio_lock:
            output = AUDIO_DIR / "yamnet_latest.wav"
            waveform, rate = record_waveform(
                seconds=seconds,
                device=self.args.audio_device,
                output=output,
                sample_rate=self.args.audio_rate,
            )
            return self._analyze_cry_waveform(waveform, rate, output=output, save_audio=False)

    def _yamnet_loop(self) -> None:
        while not self._yamnet_stop.is_set():
            try:
                self._ensure_audio_recorder()
                waveform, rate = self._latest_yamnet_window()
                min_samples = int(self.args.audio_rate * min(1.0, float(self.args.yamnet_seconds)))
                if waveform.size < min_samples:
                    previous = self.latest_yamnet or {}
                    self.latest_yamnet = {
                        **previous,
                        "status": "warming",
                        "capture": self._audio_recorder.status() if self._audio_recorder else None,
                        "updated_at": time.time(),
                    }
                else:
                    self._analyze_cry_waveform(waveform, rate, save_audio=False)
            except Exception as exc:
                self.latest_yamnet = {
                    "status": "error",
                    "error": str(exc),
                    "capture": self._audio_recorder.status() if self._audio_recorder else None,
                    "updated_at": time.time(),
                }
                if self._audio_recorder is not None:
                    self._audio_recorder.stop()
                    self._audio_recorder = None
                if self._yamnet_stop.wait(1.0):
                    break
                continue
            self._yamnet_stop.wait(max(0.1, float(self.args.yamnet_interval)))

    def _ensure_audio_recorder(self) -> None:
        if self._audio_recorder is None:
            buffer_seconds = max(8.0, float(self.args.yamnet_seconds) * 3.0)
            self._audio_recorder = ContinuousWaveformRecorder(
                device=self.args.audio_device,
                sample_rate=self.args.audio_rate,
                buffer_seconds=buffer_seconds,
                chunk_seconds=max(0.05, min(0.25, float(self.args.yamnet_interval))),
            )
        if not self._audio_recorder.running:
            self._audio_recorder.start()

    def _latest_yamnet_window(self) -> tuple[np.ndarray, int]:
        if self._audio_recorder is None:
            raise RuntimeError("audio recorder is not initialized")
        return self._audio_recorder.latest_window(self.args.yamnet_seconds)

    def _analyze_cry_waveform(
        self,
        waveform: np.ndarray,
        rate: int,
        output: Path | None = None,
        save_audio: bool = True,
    ) -> dict[str, Any]:
        if output is None:
            output = AUDIO_DIR / "yamnet_latest.wav"
        if save_audio:
            write_waveform(output, waveform, rate)
        with self.yamnet_lock:
            if self._yamnet is None:
                self._yamnet = YamnetCryDetector(self.args.yamnet_model, self.args.yamnet_labels)
            result = self._yamnet.infer_waveform(waveform, rate)
            result.update(self._cry_state.update(float(result["cry_score"])))
            self._yamnet_checks += 1
            self._yamnet_checked_at.append(time.time())
            result["checks"] = self._yamnet_checks
        result["file"] = str(output)
        result["audio_url"] = f"/audio/{output.name}"
        result["status"] = "ok"
        result["capture"] = self._audio_recorder.status() if self._audio_recorder else None
        self.latest_yamnet = result
        return result

    def _audio_level_from_waveform(self, output: Path, waveform: np.ndarray, rate: int) -> dict[str, Any]:
        if waveform.size == 0:
            rms = peak = zero_crossing_rate = 0.0
        else:
            rms = float(np.sqrt(np.mean(waveform * waveform)))
            peak = float(np.max(np.abs(waveform)))
            signs = np.signbit(waveform)
            zero_crossing_rate = float(np.count_nonzero(signs[1:] != signs[:-1]) / max(1, len(signs) - 1))
        dbfs = -120.0 if rms <= 1e-8 else 20.0 * float(np.log10(rms))
        return {
            "file": str(output),
            "audio_url": f"/audio/{output.name}",
            "sample_rate": rate,
            "duration_s": round(waveform.size / max(1, rate), 3),
            "elapsed_ms": 0,
            "rms": round(rms, 5),
            "peak": round(peak, 5),
            "dbfs": round(dbfs, 2),
            "zero_crossing_rate": round(zero_crossing_rate, 5),
            "engine": "continuous_level_meter",
        }
