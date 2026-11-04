"""Serve the photo review and allowlisted production renderer assets on localhost."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
import argparse

MOCK = Path(__file__).resolve().parent
REPOSITORY = MOCK.parent.parent
ENGINE = REPOSITORY / "app/src/main/assets/screensaver"
ENGINE_FILES = {
    "index.html", "styles.css", "photos.js", "lib-engine.js",
    "modes.js", "mode-impls.js", "scheduler.js", "photo-doodles.svg",
}


class ReviewHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if urlsplit(self.path).path == "/production/index.html":
            # Execute the actual entry point with an inert local host bridge.
            source = (ENGINE / "index.html").read_text()
            source = source.replace("<head>", '<head><script src="/renderer-fixture.js"></script>', 1)
            body = source.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    def translate_path(self, path):
        clean = unquote(urlsplit(path).path)
        if clean.startswith("/production/") and clean.count("/") == 2:
            name = clean.split("/")[-1]
            if name in ENGINE_FILES:
                return str(ENGINE / name)
        target = (MOCK / clean.lstrip("/")).resolve()
        if target.is_relative_to(MOCK) and (target.is_file() or target == MOCK):
            return str(target / "index.html" if target == MOCK else target)
        return str(MOCK / "__missing__")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default=8892, type=int)
    options = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", options.port), ReviewHandler).serve_forever()
