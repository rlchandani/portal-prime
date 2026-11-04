#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Actual restore script/CLI acceptance using only generated loopback data."""

import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[3]
KIT = ROOT / "provisioning"
BINARY = KIT / "fleetctl"
SERIAL = "public-preferences-loopback"
TOKEN = "PUBLIC-PREFERENCES-FIXTURE"


class PreferenceLoopbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="public-preference-loopback-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root.chmod(0o700)
        self.backup = self.root / "backup"
        self.preferences = self.backup / "shared_prefs"
        self.preferences.mkdir(parents=True, mode=0o700)
        self.original = b'<?xml version="1.0"?><map><string name="x">A&amp;B</string></map>\n'
        (self.preferences / "prefs.xml").write_bytes(self.original)
        self.registry = self.root / "registry"
        self.registry.mkdir(mode=0o700)
        self.scratch = self.root / "scratch"
        self.scratch.mkdir(mode=0o700)
        self.requests = []
        self.mutate = None
        self.reject_push = False
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                url = urlsplit(self.path)
                fixture.requests.append({
                    "endpoint": url.path,
                    "path": parse_qs(url.query).get("path", [None])[0],
                    "body": body,
                    "authorized": self.headers.get("Authorization") == "Bearer " + TOKEN,
                })
                if fixture.mutate and url.path == "/fs/write":
                    fixture.mutate.write_bytes(b"<map/>")
                response = {"ok": not (fixture.reject_push and url.path == "/fs/write")}
                data = json.dumps(response).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)
                self.close_connection = True

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.close_server)
        record = self.registry / (SERIAL + ".json")
        record.write_text(json.dumps({
            "serial": SERIAL, "name": "Public Preferences Fixture", "ip": "127.0.0.1",
            "agentPort": self.server.server_port, "token": TOKEN,
        }))
        record.chmod(0o600)

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(5)
        self.assertFalse(self.worker.is_alive(), "owned fixture server must retire")

    def manifest(self, names, package="com.immortal.launcher"):
        lines = ["device:  " + SERIAL]
        if package is not None:
            lines.append("package: " + package)
        lines.extend(["shared_prefs:", *("  " + name for name in names)])
        (self.backup / "MANIFEST.txt").write_text("\n".join(lines) + "\n")

    def restore(self, *flags):
        result = subprocess.run(
            ["/bin/bash", str(KIT / "fleet-restore.sh"), SERIAL,
             "--backup", str(self.backup), "--prefs", *flags],
            env={**os.environ, "FLEETCTL": str(BINARY), "PYTHON3": "python3",
                 "IMMORTAL_FLEET_DIR": str(self.registry),
                 "TMPDIR": str(self.scratch), "PYTHONDONTWRITEBYTECODE": "1"},
            capture_output=True, timeout=15)
        self.assertEqual(list(self.scratch.iterdir()), [])
        self.assertNotIn(TOKEN.encode(), result.stdout + result.stderr)
        self.assertTrue(all(request["authorized"] for request in self.requests))
        return result

    def test_late_invalid_map_has_no_http_mutation(self):
        (self.preferences / "z.xml").write_bytes(b"<map>")
        result = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])

    def test_missing_manifest_member_has_no_http_mutation(self):
        self.manifest(["prefs.xml", "missing.xml"])
        result = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])

    def test_debug_package_has_no_http_mutation(self):
        self.manifest(["prefs.xml"], "com.immortal.launcher.debug")
        result = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])

    def test_all_bytes_are_frozen_before_first_http_write(self):
        self.mutate = self.preferences / "z.xml"
        self.mutate.write_bytes(self.original)
        result = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([request["endpoint"] for request in self.requests],
                         ["/fs/write", "/fs/write", "/action"])
        self.assertEqual([request["body"] for request in self.requests[:2]],
                         [self.original, self.original])

    def test_unicode_name_exact_bytes_and_token_skip(self):
        name = " café\u2028photo.xml"
        (self.preferences / "prefs.xml").rename(self.preferences / name)
        (self.preferences / "fleet_agent.xml").write_bytes(b"old token is not XML")
        self.manifest([name, "fleet_agent.xml"])
        result = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([request["endpoint"] for request in self.requests],
                         ["/fs/write", "/action"])
        self.assertEqual(self.requests[0]["path"],
                         "/data/data/com.immortal.launcher/shared_prefs/" + name)
        self.assertEqual(self.requests[0]["body"], self.original)
        self.assertEqual(json.loads(self.requests[1]["body"]), {"action": "reboot"})

    def test_legacy_manifest_keeps_release_restore(self):
        self.manifest(["prefs.xml"], package=None)
        result = self.restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.requests[0]["body"], self.original)
        self.assertEqual(len(self.requests), 2)

    def test_invalid_dry_run_has_no_http_mutation(self):
        (self.preferences / "z.xml").write_bytes(b"<wrong/>")
        result = self.restore("--dry-run")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])

    def test_failed_push_stops_without_reboot_or_retry(self):
        self.reject_push = True
        result = self.restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([request["endpoint"] for request in self.requests], ["/fs/write"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit", type=Path, default=KIT)
    parser.add_argument("--cli", type=Path, default=BINARY)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    KIT, BINARY = args.kit.resolve(), args.cli.resolve()
    log = io.StringIO()
    result = unittest.TextTestRunner(stream=log, verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(PreferenceLoopbackTest))
    record = {
        "tests": result.testsRun, "failures": len(result.failures),
        "errors": len(result.errors), "skipped": len(result.skipped),
        "failedMethods": [str(test).split(" ")[0] for test, _ in result.failures],
        "log": log.getvalue(),
    }
    args.output.write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({key: value for key, value in record.items() if key != "log"}))
    raise SystemExit(not result.wasSuccessful())
