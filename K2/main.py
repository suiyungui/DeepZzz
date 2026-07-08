from __future__ import annotations

from k2edge.config import parse_config


def main() -> None:
    config = parse_config()
    try:
        from k2edge.app import create_app_runtime, serve
    except ModuleNotFoundError as exc:
        if exc.name != "flask":
            raise
        from network.dataset_http import serve_dataset
        from network.edge_http import serve
        from services.state import EdgeState
        from utils.paths import ensure_runtime_dirs
        import threading

        ensure_runtime_dirs()
        runtime = EdgeState(config.to_namespace())
        runtime.start()
        if config.dataset.enable_dataset_capture:
            threading.Thread(
                target=serve_dataset,
                args=(config.server.host, config.server.dataset_capture_port, runtime),
                daemon=True,
            ).start()
        try:
            serve(config.server.host, config.server.port, runtime)
        finally:
            runtime.stop()
        return
    runtime = create_app_runtime(config)
    serve(runtime)


if __name__ == "__main__":
    main()
