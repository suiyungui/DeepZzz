from __future__ import annotations

from pathlib import Path
from typing import Sequence


def create_session(model_path: str | Path):
    import spacemit_ort  # noqa: F401 - registers SpaceMITExecutionProvider
    import onnxruntime as ort

    providers: Sequence[str] = ["SpaceMITExecutionProvider", "CPUExecutionProvider"]
    return ort.InferenceSession(str(model_path), providers=list(providers))
