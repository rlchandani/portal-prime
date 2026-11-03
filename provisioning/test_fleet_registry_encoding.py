#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Real registry readers with generated files and exclusively owned loopback peers."""

import contextlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest


HELPER = Path(__file__).with_name("room-fleet.py")
SPEC = importlib.util.spec_from_file_location("room_fleet_registry_encoding", HELPER)
fleet = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = fleet
SPEC.loader.exec_module(fleet)

BOM = b"\xef\xbb\xbf"


class FleetRegistryEncodingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value = os.environ.get("IMMORTAL_FLEET_CLI")
        if not value or not Path(value).is_file() or not os.access(value, os.X_OK):
            raise RuntimeError("Set IMMORTAL_FLEET_CLI to a freshly compiled executable fleetctl")
        cls.binary = str(Path(value).resolve())

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="public-fleet-registry-encoding-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.registry.mkdir(mode=0o700)
        self.env = {**os.environ, "IMMORTAL_FLEET_DIR": str(self.registry)}
        self.original_bytes = {}
        self.addCleanup(self.assert_files_unchanged)

    def record(self, name="Generated Room", serial="GENERATED-SERIAL", port=8723, **fields):
        data = {"name": name, "serial": serial, "model": "Generated Portal",
                "ip": "127.0.0.1", "agentPort": port, "token": "PUBLIC-ENCODING-TOKEN"}
        data.update(fields)
        return data

    def write_bytes(self, filename, payload):
        path = self.registry / filename
        path.write_bytes(payload)
        path.chmod(0o600)
        self.original_bytes[path] = payload
        return path

    def write_record(self, filename, data, bom=False, crlf=False, whitespace=""):
        text = json.dumps(data, ensure_ascii=False, indent=2)
        if crlf:
            text = text.replace("\n", "\r\n") + "\r\n"
        return self.write_bytes(filename, (BOM if bom else b"") +
                                whitespace.encode("ascii") + text.encode("utf-8"))

    def assert_files_unchanged(self):
        for path, payload in self.original_bytes.items():
            self.assertEqual(path.read_bytes(), payload, "Registry reader rewrote " + path.name)

    def load_successfully(self):
        try:
            return fleet.load_rooms(self.registry)
        except fleet.FleetError as error:
            self.fail("Valid generated registry was rejected: " + str(error))

    def run_cli(self, *args):
        return subprocess.run([self.binary, *args], env=self.env, cwd=self.root,
                              capture_output=True, timeout=4)

    def assert_inventory(self, result, data):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for key in ("name", "serial", "model"):
            self.assertIn(data[key].encode("utf-8"), result.stdout)
        self.assertEqual(result.stderr, b"")

    @contextlib.contextmanager
    def peer(self):
        requests, errors = [], []
        stopped = threading.Event()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            listener.bind(("127.0.0.1", 0))
            listener.listen(4)
            listener.settimeout(0.05)

            def serve():
                while not stopped.is_set():
                    try:
                        connection, _ = listener.accept()
                    except socket.timeout:
                        continue
                    except OSError as error:
                        if not stopped.is_set():
                            errors.append(error)
                        return
                    try:
                        with connection:
                            connection.settimeout(1)
                            header = b""
                            while b"\r\n\r\n" not in header:
                                chunk = connection.recv(4096)
                                if not chunk:
                                    raise AssertionError("Generated request ended before headers")
                                header += chunk
                                if len(header) > 8192:
                                    raise AssertionError("Generated request exceeded header bound")
                            requests.append(header)
                            body = b'{"ok":true,"fixture":"owned-registry-encoding"}'
                            connection.sendall(
                                b"HTTP/1.1 200 Fixture\r\nContent-Length: " +
                                str(len(body)).encode("ascii") +
                                b"\r\nConnection: close\r\n\r\n" + body)
                    except Exception as error:
                        errors.append(error)

            worker = threading.Thread(target=serve, name="owned-registry-encoding-peer")
            worker.start()
            try:
                yield listener.getsockname()[1], requests
            finally:
                stopped.set()
                listener.close()
                worker.join(2)
                self.assertFalse(worker.is_alive(), "Owned loopback worker did not retire")
                if errors:
                    raise errors[0]
        finally:
            listener.close()

    def assert_request(self, request, data):
        self.assertTrue(request.startswith(b"GET /info HTTP/1.1\r\n"), request)
        self.assertIn(("Host: 127.0.0.1:" + str(data["agentPort"]) + "\r\n").encode(),
                      request)
        self.assertIn(("Authorization: Bearer " + data["token"] + "\r\n").encode("utf-8"),
                      request)

    def test_cli_accepts_single_utf8_bom_and_crlf_inventory(self):
        data = self.record()
        self.write_record("windows.json", data, bom=True, crlf=True, whitespace="\r\n")
        self.assert_inventory(self.run_cli("devices"), data)

    def test_room_loader_accepts_single_utf8_bom_and_crlf(self):
        data = self.record()
        path = self.write_record("windows.json", data, bom=True, crlf=True)
        rooms = self.load_successfully()
        self.assertEqual(len(rooms), 1)
        self.assertEqual(rooms[0].path, path)
        self.assertEqual(rooms[0].data, data)
        self.assertEqual(fleet.select_rooms(rooms, data["name"]), rooms)
        self.assertEqual(fleet.select_rooms(rooms, data["serial"]), rooms)

    def test_cli_unicode_bom_registry_selects_owned_peer_and_token(self):
        with self.peer() as (port, requests):
            data = self.record(name="Café 北京", serial="SÉRIAL-東京", port=port,
                               model="Portal é 🌍", token="PUBLIC-TOKEN-é-東京")
            self.write_record("unicode.json", data, bom=True)
            self.assert_inventory(self.run_cli("devices"), data)
            result = self.run_cli("info", "--device", data["serial"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_request(requests[0], data)
        self.assertEqual(json.loads(result.stdout), {"ok": True, "fixture": "owned-registry-encoding"})

    def test_room_loader_preserves_unicode_fields_and_exact_selection(self):
        data = self.record(name="Café 北京", serial="SÉRIAL-東京", model="Portal é 🌍",
                           token="PUBLIC-TOKEN-é-東京", extra={"retain": "雪"})
        self.write_record("unicode.json", data, bom=True)
        rooms = self.load_successfully()
        self.assertEqual(len(rooms), 1)
        self.assertEqual(rooms[0].data, data)
        self.assertEqual(fleet.select_rooms(rooms, data["serial"]), rooms)
        self.assertEqual(fleet.select_rooms(rooms, data["name"]), rooms)

    def test_cli_mixed_encodings_reach_both_generated_peers(self):
        with self.peer() as (first_port, first_requests), self.peer() as (second_port, second_requests):
            first = self.record(name="First", serial="FIRST", port=first_port,
                                token="PUBLIC-FIRST")
            second = self.record(name="Second", serial="SECOND", port=second_port,
                                 token="PUBLIC-SECOND")
            self.write_record("first.json", first, bom=True)
            self.write_record("second.json", second)
            result = self.run_cli("info", "--device", "all")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(first_requests), 1, "BOM-prefixed target was silently omitted")
        self.assertEqual(len(second_requests), 1)
        self.assert_request(first_requests[0], first)
        self.assert_request(second_requests[0], second)

    def test_room_loader_reads_mixed_encodings_without_rewriting(self):
        first = self.record(name="First", serial="FIRST")
        second = self.record(name="Second", serial="SECOND")
        self.write_record("first.json", first, bom=True)
        self.write_record("second.json", second)
        rooms = self.load_successfully()
        self.assertEqual([room.data for room in rooms], [first, second])
        self.assertEqual(fleet.select_rooms(rooms, "all"), rooms)

    def test_bom_free_registry_remains_usable_in_both_readers(self):
        with self.peer() as (port, requests):
            data = self.record(port=port)
            self.write_record("plain.json", data, crlf=True)
            self.assert_inventory(self.run_cli("devices"), data)
            self.assertEqual(self.load_successfully()[0].data, data)
            result = self.run_cli("info", "--device", data["name"].lower())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_request(requests[0], data)

    def test_valid_embedded_bom_character_is_preserved_as_field_data(self):
        # U+FEFF inside a JSON string is data, not a file encoding marker.
        data = self.record(name="Room\ufeffName", serial="SERIAL\ufeffEND",
                           model="Model\ufeffText", token="PUBLIC-\ufeff-TOKEN")
        self.write_record("embedded.json", data)
        self.assert_inventory(self.run_cli("devices"), data)
        rooms = self.load_successfully()
        self.assertEqual(rooms[0].data, data)
        self.assertEqual(fleet.select_rooms(rooms, data["name"]), rooms)

    def assert_malformed_record_policy(self, payload):
        valid = self.record(name="Valid Sentinel", serial="VALID-SENTINEL")
        self.write_record("valid.json", valid)
        self.write_bytes("malformed.json", payload)
        result = self.run_cli("devices")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(valid["name"].encode(), result.stdout)
        self.assertNotIn(b"MALFORMED-SERIAL", result.stdout)
        self.assertIn(b"warning: skipping", result.stderr)
        with self.assertRaises(fleet.FleetError):
            fleet.load_rooms(self.registry)
        self.assert_files_unchanged()

    def test_malformed_bom_positions_keep_skip_and_strict_reader_policies(self):
        raw = json.dumps(self.record(name="Malformed", serial="MALFORMED-SERIAL")).encode()
        for label, payload in [
            ("double", BOM + BOM + raw),
            ("after-whitespace", b" \r\n" + BOM + raw),
            ("interior-syntax", raw.replace(b'"serial"', BOM + b'"serial"', 1)),
            ("marker-only", BOM),
        ]:
            with self.subTest(position=label):
                self.assert_malformed_record_policy(payload)

    def test_invalid_utf8_and_utf16_are_not_accepted(self):
        text = json.dumps(self.record(name="Malformed", serial="MALFORMED-SERIAL"))
        raw = text.encode()
        invalid_utf8 = raw.replace(b"Malformed", b"Malformed\xff", 1)
        for label, payload in [
            ("invalid-utf8", invalid_utf8),
            ("bom-invalid-utf8", BOM + invalid_utf8),
            ("utf16le", b"\xff\xfe" + text.encode("utf-16-le")),
            ("utf16be", b"\xfe\xff" + text.encode("utf-16-be")),
        ]:
            with self.subTest(encoding=label):
                self.assert_malformed_record_policy(payload)


if __name__ == "__main__":
    unittest.main()
