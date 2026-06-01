from __future__ import annotations

import signal
import threading
from typing import Any

from config import parse_args


def main() -> None:
    config = parse_args()

    from network.dataset_http import serve_dataset
    from network.edge_http import serve
    from services.state import EdgeState
    from utils.paths import ensure_runtime_dirs

    ensure_runtime_dirs()
    state = EdgeState(config)

    def shutdown(signum: int, frame: Any) -> None:
        state.stop()
        raise SystemExit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    state.start()
    if config.enable_dataset_capture:
        threading.Thread(
            target=serve_dataset,
            args=(config.host, config.dataset_capture_port, state),
            daemon=True,
        ).start()
    try:
        serve(config.host, config.port, state)
    finally:
        state.stop()


if __name__ == "__main__":
    main()
