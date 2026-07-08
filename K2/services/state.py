from __future__ import annotations

import argparse
from typing import Any

from k2edge.config import build_config, namespace_to_values
from k2edge.runtime import AppRuntime, estimated_npu_percent, normalize_dataset_labels


class EdgeState(AppRuntime):
    """Compatibility wrapper for older imports.

    New code should use k2edge.runtime.AppRuntime directly.
    """

    def __init__(self, args: argparse.Namespace) -> None:
        super().__init__(build_config(namespace_to_values(args)))


__all__ = ["EdgeState", "estimated_npu_percent", "normalize_dataset_labels"]
