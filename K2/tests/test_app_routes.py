from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import wave

import numpy as np

from k2edge.app import create_dataset_app, create_main_app
from k2edge.config import parse_config
from k2edge.runtime import AppRuntime
from network.dataset_http import serve_dataset
from network.edge_http import serve


def make_runtime() -> AppRuntime:
    return AppRuntime(parse_config(["--no-camera", "--disable-yamnet", "--enable-dataset-capture"]))


class AppRouteTests(unittest.TestCase):
    def test_healthz_and_status_shapes(self) -> None:
        runtime = make_runtime()
        client = create_main_app(runtime).test_client()

        health = client.get("/api/healthz")
        self.assertEqual(health.status_code, 200)
        self.assertIs(health.get_json()["ok"], True)
        self.assertIn("runtime", health.get_json())

        status = client.get("/api/status")
        payload = status.get_json()
        self.assertEqual(status.status_code, 200)
        self.assertIs(payload["ok"], True)
        self.assertIn("camera", payload)
        self.assertIn("light", payload)
        self.assertIn("temperature_humidity", payload)

    def test_snapshot_returns_compatible_error_shape_when_empty(self) -> None:
        runtime = make_runtime()
        client = create_main_app(runtime).test_client()

        response = client.get("/api/snapshot")

        self.assertEqual(response.status_code, 404)
        self.assertIs(response.get_json()["ok"], False)

    def test_missing_hls_file_keeps_json_error_shape(self) -> None:
        runtime = make_runtime()
        client = create_main_app(runtime).test_client()

        response = client.get("/hls/missing.m3u8")

        self.assertEqual(response.status_code, 404)
        self.assertIs(response.get_json()["ok"], False)

    def test_light_post_writes_command_file(self) -> None:
        runtime = make_runtime()
        with tempfile.TemporaryDirectory() as tmp:
            runtime.hardware.light.command_path = Path(tmp) / "light_command.json"
            runtime.hardware.light.status_path = Path(tmp) / "light_status.json"
            client = create_main_app(runtime).test_client()

            response = client.post("/api/light", json={"duty": 42})
            payload = response.get_json()

            self.assertEqual(response.status_code, 200)
            self.assertIs(payload["ok"], True)
            self.assertEqual(payload["light"]["target_duty"], 42.0)
            self.assertTrue(runtime.hardware.light.command_path.exists())

    def test_dataset_stats_route(self) -> None:
        runtime = make_runtime()
        client = create_dataset_app(runtime).test_client()

        response = client.get("/api/dataset/stats")
        payload = response.get_json()

        self.assertEqual(response.status_code, 200)
        self.assertIs(payload["ok"], True)
        self.assertIs(payload["result"]["enabled"], True)

    def test_audio_playback_route_records_when_no_buffer(self) -> None:
        runtime = make_runtime()
        client = create_main_app(runtime).test_client()

        with tempfile.TemporaryDirectory() as tmp:
            runtime.audio.pipeline.args.audio_device = "input"
            with patch("pipelines.audio_pipeline.AUDIO_DIR", Path(tmp)):
                with patch("pipelines.audio_pipeline.record_waveform") as record_waveform:
                    with patch("pipelines.audio_pipeline.subprocess.run") as run:
                        record_waveform.return_value = (np.zeros(16000, dtype=np.float32), 16000)
                        run.return_value.returncode = 0
                        run.return_value.stdout = ""
                        run.return_value.stderr = ""

                        response = client.post(
                            "/api/audio/playback",
                            json={"seconds": 1, "volume": 1.2, "output_device": "default"},
                        )
                        thread = runtime.audio.pipeline._playback_thread
                        if thread is not None:
                            thread.join(timeout=2)
                        payload = response.get_json()
                        with wave.open(payload["result"]["file"], "rb") as handle:
                            self.assertEqual(handle.getframerate(), 48000)
                            self.assertEqual(handle.getnchannels(), 2)

        payload = response.get_json()
        self.assertEqual(response.status_code, 200)
        self.assertIs(payload["ok"], True)
        self.assertEqual(payload["result"]["output_device"], "sysdefault:CARD=sndes8326")
        self.assertEqual(payload["result"]["volume"], 1.2)
        record_waveform.assert_called_once()

    def test_audio_playback_capture_start_stop_and_play(self) -> None:
        runtime = make_runtime()
        client = create_main_app(runtime).test_client()

        with tempfile.TemporaryDirectory() as tmp:
            with patch("pipelines.audio_pipeline.AUDIO_DIR", Path(tmp)):
                with patch("pipelines.audio_pipeline.time.time") as now:
                    with patch("pipelines.audio_pipeline.record_waveform") as record_waveform:
                        with patch("pipelines.audio_pipeline.subprocess.run") as run:
                            ticks = iter([100.0, 100.0, 102.0, 102.0, 102.0, 102.0, 102.0, 102.0])
                            now.side_effect = lambda: next(ticks, 102.0)
                            record_waveform.return_value = (np.zeros(32000, dtype=np.float32), 16000)
                            run.return_value.returncode = 0
                            run.return_value.stdout = ""
                            run.return_value.stderr = ""

                            start = client.post("/api/audio/playback/capture/start")
                            stop = client.post("/api/audio/playback/capture/stop", json={"volume": 1.0})
                            play = client.post(
                                "/api/audio/playback/play",
                                json={"output_device": "default", "volume": 1.0},
                            )
                            thread = runtime.audio.pipeline._playback_thread
                            if thread is not None:
                                thread.join(timeout=2)

        self.assertEqual(start.status_code, 200)
        self.assertEqual(stop.status_code, 200)
        self.assertEqual(play.status_code, 200)
        self.assertIs(play.get_json()["ok"], True)
        self.assertEqual(play.get_json()["result"]["output_device"], "sysdefault:CARD=sndes8326")
        record_waveform.assert_called_once()

    def test_legacy_network_modules_still_export_servers(self) -> None:
        self.assertTrue(callable(serve))
        self.assertTrue(callable(serve_dataset))


if __name__ == "__main__":
    unittest.main()
