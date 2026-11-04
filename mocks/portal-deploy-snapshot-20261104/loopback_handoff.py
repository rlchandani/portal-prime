#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Actual existing fleetctl + actual deploy helper against an owned loopback server."""
from contextlib import redirect_stdout
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from urllib.parse import parse_qs, urlsplit

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SOURCE = Path(os.environ.get("PORTAL_DEPLOY_SOURCE", str(HERE.parents[1] / "provisioning/room-fleet.py")))
CLI = Path(os.environ.get("IMMORTAL_FLEET_CLI", str(HERE.parents[1] / "provisioning/fleetctl")))
spec = importlib.util.spec_from_file_location("loopback_snapshot_fleet", SOURCE)
fleet = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = fleet
spec.loader.exec_module(fleet)
selected = b"generated selected APK A"
replacement = b"generated replacement APK B"
package = "com.immortal.launcher.debug"
cases = []
for mutate in (False, True):
    with tempfile.TemporaryDirectory(prefix="snapshot-realcli-", dir=HERE) as directory:
        root = Path(directory).resolve()
        root.chmod(0o700)
        apk = root / "selected.apk"
        apk.write_bytes(selected)
        requests, uploads, installs = [], [], []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body):
                body = json.dumps(body).encode() if isinstance(body, dict) else body
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                requests.append(["GET", self.path])
                if self.headers.get("Authorization") != "Bearer generated-loopback-only":
                    self.send_error(403)
                    return
                if self.path == "/info":
                    if mutate:
                        apk.write_bytes(replacement)
                    self.reply({"ok": True, "name": "Generated Room"})
                elif urlsplit(self.path).path == "/fs/read":
                    path = parse_qs(urlsplit(self.path).query)["path"][0]
                    if path != "/proc/self/maps":
                        raise AssertionError("Unexpected helper file request")
                    # No target APK is mapped: this is the existing nonself ACK branch.
                    self.reply(b"1000-2000 r--p 0000 00:00 1 /data/app/other.app-x/base.apk\n")
                else:
                    self.send_error(404)

            def do_POST(self):
                requests.append(["POST", self.path])
                if self.headers.get("Authorization") != "Bearer generated-loopback-only":
                    self.send_error(403)
                    return
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                path = urlsplit(self.path).path
                if path == "/dev":
                    self.reply({"ok": True, "enabled": True})
                elif path == "/fs/write":
                    uploads.append(body)
                    self.reply({"ok": True})
                elif path == "/install":
                    installs.append(json.loads(body))
                    self.reply({"ok": True, "result": "installed", "packageName": package})
                else:
                    self.send_error(404)

        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            record = {"name": "Generated Room", "serial": "generated-loopback-only",
                      "ip": "127.0.0.1", "agentPort": server.server_port,
                      "token": "generated-loopback-only"}
            registry = root / "generated.json"
            registry.write_text(json.dumps(record))
            registry.chmod(0o600)
            rooms = fleet.load_rooms(root)
            output = io.StringIO()
            with redirect_stdout(output):
                result = fleet.deploy(CLI, root, rooms, apk, package)
            cases.append({
                "mutateOriginalAtInfoAfterHash": mutate, "result": result,
                "uploadCount": len(uploads), "installRequestCount": len(installs),
                "uploadSha256": [hashlib.sha256(body).hexdigest() for body in uploads],
                "uploadedSelectedBytes": uploads == [selected],
                "selectedSha256": hashlib.sha256(selected).hexdigest(),
                "requests": requests, "output": output.getvalue(),
                "scope": "Actual existing native CLI byte read/upload/install request; synthetic loopback ACK only, no Android installer.",
            })
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)
            if worker.is_alive():
                raise AssertionError("Owned loopback server did not retire")

print(json.dumps({
    "source": str(SOURCE), "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
    "cli": str(CLI), "cliSha256": hashlib.sha256(CLI.read_bytes()).hexdigest(),
    "cases": cases, "ownedCleanup": True,
}, indent=2))
