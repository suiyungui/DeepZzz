from __future__ import annotations

from pathlib import Path
from typing import Sequence


CPU_PROVIDER = "CPUExecutionProvider"
NPU_PROVIDER = "SpaceMITExecutionProvider"


def ordered_providers(provider: str | None, fallback: bool = True) -> list[str]:
    provider = (provider or CPU_PROVIDER).strip()
    providers: list[str] = []
    if provider == NPU_PROVIDER:
        try:
            import spacemit_ort  # noqa: F401 - registers SpaceMITExecutionProvider
        except Exception:
            if not fallback:
                raise
        else:
            providers.append(NPU_PROVIDER)
    elif provider:
        providers.append(provider)
    if fallback and CPU_PROVIDER not in providers:
        providers.append(CPU_PROVIDER)
    return providers


def create_session(
    model_path: str | Path,
    provider: str | None = None,
    fallback: bool = True,
    session_options=None,
):
    import onnxruntime as ort

    kwargs = {"providers": ordered_providers(provider, fallback=fallback)}
    if session_options is not None:
        kwargs["sess_options"] = session_options
    return ort.InferenceSession(str(model_path), **kwargs)


def available_providers() -> Sequence[str]:
    import onnxruntime as ort

    return ort.get_available_providers()


def provider_label(provider: str | None) -> str:
    if provider == NPU_PROVIDER:
        return "spacemit"
    if provider == CPU_PROVIDER:
        return "cpu"
    if not provider:
        return "unknown"
    return provider.removesuffix("ExecutionProvider").lower()
