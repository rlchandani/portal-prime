#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Local review server exposes only named renderer assets and synthetic fixtures."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
import mimetypes
import json

here = Path(__file__).resolve().parent
repo = here.parents[2]
renderer = repo / "app/src/main/assets/screensaver"
files = {"/": here / "index.html"}
for name in ("index.html", "styles.css", "photos.js", "lib-engine.js", "modes.js",
             "mode-impls.js", "scheduler.js"):
    files["/screensaver/" + name] = renderer / name
for name in ("dunes", "tidal"):
    files["/fixtures/" + name + ".png"] = here / "assets" / (name + ".png")
for name in ("gallery-01.svg", "gallery-02.svg", "gallery-03.svg", "gallery-04.svg",
             "gallery-05.svg", "gallery-06.svg", "gallery-07.svg", "gallery-08.svg",
             "gallery-09.svg", "gallery-10.svg", "gallery-11.svg", "gallery-12.svg",
             "gallery-fixtures.json"):
    files["/fixtures/" + name] = here / "assets" / name
gallery_photos = json.loads(files["/fixtures/gallery-fixtures.json"].read_text())


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if urlsplit(self.path).path == "/preview.html":
            mode = parse_qs(urlsplit(self.path).query).get("mode", ["diptych"])[0]
            if mode not in ("diptych", "framed", "kenBurns", "push", "galleryWall", "heroSidebar"):
                self.send_error(400)
                return
            photos = gallery_photos if mode in ("galleryWall", "heroSidebar") else [
                {"url": "/fixtures/" + name + ".png", "w": 480, "h": 720}
                for name in ("dunes", "tidal")]
            bridge = "<base href='/screensaver/'><script>window.Android={" + \
                "getPhotos:()=>" + json.dumps(json.dumps(photos)) + \
                ",getMode:()=>" + json.dumps(mode) + \
                ",getIntervalSec:()=>30,scaledUrlForSize:url=>url};</script>"
            content = (renderer / "index.html").read_text().replace("<head>", "<head>" + bridge).encode()
            content_type = "text/html"
        else:
            path = files.get(urlsplit(self.path).path)
            if path is None or not path.is_file():
                self.send_error(404)
                return
            content = path.read_bytes()
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    print("Photo renderer review: http://127.0.0.1:8891/", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8891), Handler).serve_forever()
