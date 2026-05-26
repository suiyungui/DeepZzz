#!/usr/bin/env python3
import mimetypes
import os
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent

mimetypes.add_type("application/vnd.apple.mpegurl", ".m3u8")
mimetypes.add_type("video/mp2t", ".ts")


class CameraHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        if self.path.endswith((".m3u8", ".ts")):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Pragma", "no-cache")
            self.send_header("Expires", "0")
        super().end_headers()

    def do_GET(self):
        if self.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        return super().do_GET()


def main():
    os.chdir(BASE_DIR)
    port = int(os.environ.get("WEB_PORT", "8080"))
    server = ThreadingHTTPServer(("0.0.0.0", port), CameraHandler)
    print(f"Camera web server listening on 0.0.0.0:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
