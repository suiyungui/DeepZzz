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
LULLABY_DIR = RUNTIME_DIR / "lullabies"
LULLABY_MANIFEST_FILE = LULLABY_DIR / "manifest.json"
LOG_DIR = RUNTIME_DIR / "logs"
DATASET_DIR = RUNTIME_DIR / "dataset"

DAY_NIGHT_SCRIPT = SCRIPTS_DIR / "day_night.py"
DAY_NIGHT_LOG = RUNTIME_DIR / "logs" / "sync_day_night_ircut.log"
DAY_NIGHT_STATE_FILE = RUNTIME_DIR / "state" / "day_night"
IRCUT_SCRIPT = SCRIPTS_DIR / "ir_cut_control.py"
IRCUT_MODE_FILE = RUNTIME_DIR / "state" / "current_mode"
LIGHT_COMMAND_FILE = RUNTIME_DIR / "state" / "light_command.json"
LIGHT_STATUS_FILE = RUNTIME_DIR / "state" / "light_status.json"
LIGHT_LOG = RUNTIME_DIR / "logs" / "light_pwm.log"
TEMPERATURE_HUMIDITY_STATUS_FILE = RUNTIME_DIR / "state" / "temperature_humidity.json"
TEMPERATURE_HUMIDITY_LOG = RUNTIME_DIR / "logs" / "temperature_humidity.log"
ACTIVITY_ZONE_FILE = RUNTIME_DIR / "state" / "activity_zone.json"


def ensure_runtime_dirs() -> None:
    for path in (
        HLS_DIR,
        AUDIO_DIR,
        LULLABY_DIR,
        LOG_DIR,
        DATASET_DIR,
        DAY_NIGHT_STATE_FILE.parent,
        IRCUT_MODE_FILE.parent,
        LIGHT_COMMAND_FILE.parent,
        LIGHT_LOG.parent,
        TEMPERATURE_HUMIDITY_STATUS_FILE.parent,
        TEMPERATURE_HUMIDITY_LOG.parent,
        ACTIVITY_ZONE_FILE.parent,
    ):
        path.mkdir(parents=True, exist_ok=True)
