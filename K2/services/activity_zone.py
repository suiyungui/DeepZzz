from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from k2edge.runtime_store import read_json, write_json


VALID_MODES = {"safe", "danger"}


class ActivityZoneStore:
    def __init__(self, path: Path) -> None:
        self.path = path

    def status(self) -> dict[str, Any]:
        payload = read_json(self.path, default={})
        mode = payload.get("mode")
        zone = normalize_zone(payload.get("zone"))
        if mode not in VALID_MODES or zone is None:
            return {"mode": "safe", "zone": None, "updated_at": payload.get("updated_at")}
        return {
            "mode": mode,
            "zone": zone,
            "updated_at": payload.get("updated_at"),
        }

    def update(self, payload: dict[str, Any]) -> dict[str, Any]:
        if payload.get("zone") is None:
            return self.clear()
        mode = str(payload.get("mode") or "safe").lower()
        if mode not in VALID_MODES:
            raise ValueError("invalid activity zone mode")
        zone = normalize_zone(payload.get("zone"))
        if zone is None:
            raise ValueError("invalid activity zone")
        next_payload = {
            "mode": mode,
            "zone": zone,
            "updated_at": time.time(),
        }
        write_json(self.path, next_payload)
        return self.status()

    def clear(self) -> dict[str, Any]:
        write_json(self.path, {"mode": "safe", "zone": None, "updated_at": time.time()})
        return self.status()


def normalize_zone(value: Any) -> dict[str, float] | None:
    if not isinstance(value, dict):
        return None
    try:
        left = float(value["left"])
        top = float(value["top"])
        right = float(value["right"])
        bottom = float(value["bottom"])
    except (KeyError, TypeError, ValueError):
        return None
    left = max(0.0, min(1.0, left))
    top = max(0.0, min(1.0, top))
    right = max(0.0, min(1.0, right))
    bottom = max(0.0, min(1.0, bottom))
    left, right = sorted((left, right))
    top, bottom = sorted((top, bottom))
    if right - left <= 0.01 or bottom - top <= 0.01:
        return None
    return {
        "left": round(left, 4),
        "top": round(top, 4),
        "right": round(right, 4),
        "bottom": round(bottom, 4),
    }
