from __future__ import annotations

import csv
import subprocess
import time
import wave
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort


DEFAULT_MODEL = Path("models/yamnet/yamnet.onnx")
DEFAULT_LABELS = Path("models/yamnet/yamnet_class_map.csv")
CRY_LABELS = {
    "Baby cry, infant cry",
    "Crying, sobbing",
    "Screaming",
}


class YamnetCryDetector:
    def __init__(
        self,
        model_path: str | Path = DEFAULT_MODEL,
        labels_path: str | Path = DEFAULT_LABELS,
        provider: str = "CPUExecutionProvider",
    ) -> None:
        self.model_path = str(model_path)
        self.labels = load_labels(labels_path)
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.session = ort.InferenceSession(self.model_path, sess_options=options, providers=[provider])
        self.input_name = self.session.get_inputs()[0].name
        self.output_names = [output.name for output in self.session.get_outputs()]
        self.provider = self.session.get_providers()[0]

    def infer_waveform(self, waveform: np.ndarray, sample_rate: int) -> dict[str, Any]:
        if sample_rate != 16000:
            raise ValueError(f"YAMNet expects 16000 Hz audio, got {sample_rate}")
        if waveform.dtype != np.float32:
            waveform = waveform.astype(np.float32)
        started = time.perf_counter()
        scores, _embeddings, _spectrogram = self.session.run(self.output_names, {self.input_name: waveform})
        elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
        mean_scores = scores.mean(axis=0)
        top_indices = np.argsort(mean_scores)[-10:][::-1]
        top = [
            {
                "label": self.labels[int(index)],
                "score": round(float(mean_scores[int(index)]), 4),
            }
            for index in top_indices
        ]
        cry_score = max(float(mean_scores[index]) for index, label in enumerate(self.labels) if label in CRY_LABELS)
        return {
            "engine": "yamnet_onnx",
            "provider": self.provider,
            "elapsed_ms": elapsed_ms,
            "sample_rate": sample_rate,
            "duration_s": round(float(waveform.size) / sample_rate, 3),
            "frames": int(scores.shape[0]),
            "cry_score": round(cry_score, 4),
            "crying": cry_score >= 0.35,
            "top": top,
            "updated_at": time.time(),
        }


def record_waveform(seconds: float, device: str, output: Path, sample_rate: int = 16000) -> tuple[np.ndarray, int]:
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "arecord",
        "-D",
        device,
        "-f",
        "S16_LE",
        "-r",
        str(sample_rate),
        "-c",
        "1",
        "-d",
        str(max(1, int(round(seconds)))),
        str(output),
    ]
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=seconds + 5)
    if completed.returncode != 0:
        raise RuntimeError((completed.stderr or completed.stdout or "arecord failed").strip())
    return read_waveform(output)


def read_waveform(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as handle:
        channels = handle.getnchannels()
        rate = handle.getframerate()
        frames = handle.readframes(handle.getnframes())
    samples = np.frombuffer(frames, dtype=np.int16)
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1).astype(np.int16)
    return samples.astype(np.float32) / 32768.0, rate


def load_labels(path: str | Path) -> list[str]:
    labels: list[str] = []
    with Path(path).open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            labels.append(row.get("display_name") or row.get("name") or str(len(labels)))
    return labels
