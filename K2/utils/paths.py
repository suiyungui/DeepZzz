from __future__ import annotations

from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]

WEB_DIR = PROJECT_ROOT / "web"
SCRIPTS_DIR = PROJECT_ROOT / "scripts"

STATIC_DIR = WEB_DIR / "static"
TEMPLATES_DIR = WEB_DIR / "templates"
RUNTIME_DIR = PROJECT_ROOT / "runtime"
HLS_DIR = RUNTIME_DIR / "hls"
AUDIO_DIR = RUNTIME_DIR / "audio"
LOG_DIR = RUNTIME_DIR / "logs"
DATASET_DIR = RUNTIME_DIR / "dataset"

DAY_NIGHT_SCRIPT = SCRIPTS_DIR / "day_night.py"
DAY_NIGHT_LOG = RUNTIME_DIR / "logs" / "sync_day_night_ircut.log"
DAY_NIGHT_STATE_FILE = RUNTIME_DIR / "state" / "day_night"
IRCUT_SCRIPT = SCRIPTS_DIR / "ir_cut_control.py"
IRCUT_MODE_FILE = RUNTIME_DIR / "state" / "current_mode"


def ensure_runtime_dirs() -> None:
    for path in (HLS_DIR, AUDIO_DIR, LOG_DIR, DATASET_DIR, DAY_NIGHT_STATE_FILE.parent, IRCUT_MODE_FILE.parent):
        path.mkdir(parents=True, exist_ok=True)
