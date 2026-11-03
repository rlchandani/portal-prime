#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Actual room refresh with generated registry bytes and an owned loopback peer."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import socket
import stat
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch


HELPER = Path(__file__).with_name("room-fleet.py")
SPEC = importlib.util.spec_from_file_location("room_registry_encoding", HELPER)
fleet = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = fleet
SPEC.loader.exec_module(fleet)
BOM = b"\xef\xbb\xbf"


class RoomRegistryEncodingTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="public-room-registry-encoding-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.registry.mkdir(mode=0o700)
        self.path = self.registry / "generated-room.json"
        self.data = {
            "serial": "GENERATED-東京", "name": "Café 北京", "model": "Portal é 🌍",
            "ip": "192.0.2.10", "agentPort": 8723, "token": "PUBLIC-REFRESH-TOKEN",
            "extra": {"retain": ["雪", 7, False], "nested": {"text": "é"}},
        }

    def write_record(self, data=None, bom=True, crlf=True):
        text = json.dumps(self.data if data is None else data, ensure_ascii=False, indent=2)
        if crlf:
            text = text.replace("\n", "\r\n") + "\r\n"
        payload = (BOM if bom else b"") + text.encode("utf-8")
        self.path.write_bytes(payload)
        self.path.chmod(0o600)
        return payload

    def load_room(self):
        try:
            rooms = fleet.load_rooms(self.registry)
        except fleet.FleetError as error:
            self.fail("Valid generated registry was rejected: " + str(error))
        self.assertEqual(len(rooms), 1)
        return rooms[0]

    def update_successfully(self, room, ip="127.0.0.1", port=8765):
        try:
            fleet.atomic_update(room, ip, port)
        except fleet.FleetError as error:
            self.fail("Valid generated registry update was rejected: " + str(error))

    def assert_no_temporary(self):
        self.assertEqual(sorted(path.name for path in self.registry.iterdir()),
                         [self.path.name], "Registry update left temporary files")

    def assert_updated(self, ip, port):
        self.assertEqual(json.loads(self.path.read_bytes()),
                         dict(self.data, ip=ip, agentPort=port))
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assert_no_temporary()

    @contextlib.contextmanager
    def info_peer(self):
        requests, errors = [], []
        stopped = threading.Event()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            listener.bind(("127.0.0.1", 0))
            listener.listen(2)
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
                                    raise AssertionError("Owned request ended before headers")
                                header += chunk
                                if len(header) > 8192:
                                    raise AssertionError("Owned request exceeded header bound")
                            requests.append(header)
                            body = json.dumps({
                                "ok": True, "name": self.data["name"],
                                "privateFixtureBody": "PUBLIC-BODY-NOT-FOR-OUTPUT",
                            }).encode("ascii")
                            connection.sendall(
                                b"HTTP/1.1 200 Fixture\r\nContent-Length: " +
                                str(len(body)).encode("ascii") +
                                b"\r\nConnection: close\r\n\r\n" + body)
                    except Exception as error:
                        errors.append(error)

            worker = threading.Thread(target=serve, name="owned-room-refresh-peer")
            worker.start()
            try:
                yield listener.getsockname()[1], requests
            finally:
                stopped.set()
                listener.close()
                worker.join(2)
                self.assertFalse(worker.is_alive(), "Owned refresh worker did not retire")
                if errors:
                    raise errors[0]
        finally:
            listener.close()

    def test_bom_unicode_crlf_update_changes_only_endpoint_and_replaces_inode(self):
        original = self.write_record()
        room = self.load_room()
        self.assertEqual(self.path.read_bytes(), original, "Loading rewrote the registry")
        inode = self.path.stat().st_ino
        self.update_successfully(room)
        self.assert_updated("127.0.0.1", 8765)
        self.assertNotEqual(self.path.stat().st_ino, inode)
        self.assertEqual(room.data, self.data, "Updating mutated the authenticated snapshot")

    def test_refresh_authenticates_bom_room_then_updates_and_reports_reachable(self):
        self.write_record()
        room = self.load_room()
        output = io.StringIO()
        with self.info_peer() as (port, requests):
            with patch.object(fleet, "lookup", return_value=("127.0.0.1", port)) as lookup, \
                    contextlib.redirect_stdout(output):
                result = fleet.report_rooms([room], 1, refresh=True)
            lookup.assert_called_once_with(self.data["name"], 1)
        self.assertEqual(len(requests), 1, "Refresh must authenticate before updating")
        self.assertTrue(requests[0].startswith(b"GET /info HTTP/1.1\r\n"))
        self.assertIn(("Host: 127.0.0.1:" + str(port) + "\r\n").encode(), requests[0])
        self.assertIn(("Authorization: Bearer " + self.data["token"] + "\r\n").encode(),
                      requests[0])
        self.assertEqual(result, 0, "Authenticated refresh failed: " + output.getvalue())
        self.assertEqual(output.getvalue(),
                         f'{self.data["name"]}: reachable (127.0.0.1:{port})\n')
        self.assertNotIn(self.data["token"], output.getvalue())
        self.assertNotIn("PUBLIC-BODY-NOT-FOR-OUTPUT", output.getvalue())
        self.assert_updated("127.0.0.1", port)

    def test_plain_registry_update_preserves_existing_write_contract(self):
        original = self.write_record(bom=False)
        room = self.load_room()
        self.assertEqual(self.path.read_bytes(), original)
        self.update_successfully(room)
        self.assert_updated("127.0.0.1", 8765)

    def test_loaded_bom_registry_changed_before_update_remains_untouched(self):
        for key, value in (("token", "PUBLIC-REPLACED-TOKEN"),
                           ("extra", {"retain": "changed by generated fixture"})):
            with self.subTest(changed_field=key):
                self.write_record()
                room = self.load_room()
                changed = dict(self.data, **{key: value})
                payload = self.write_record(changed)
                with self.assertRaises(fleet.FleetError):
                    fleet.atomic_update(room, "127.0.0.1", 8765)
                self.assertEqual(self.path.read_bytes(), payload)
                self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
                self.assert_no_temporary()

    def test_replace_failure_retires_private_temp_and_preserves_original_bom_bytes(self):
        original = self.write_record()
        room = self.load_room()
        temporary_paths = []

        def fail_replace(source, destination):
            temporary = Path(source)
            temporary_paths.append(temporary)
            self.assertEqual(Path(destination), self.path)
            self.assertEqual(stat.S_IMODE(temporary.stat().st_mode), 0o600)
            self.assertEqual(json.loads(temporary.read_bytes()),
                             dict(self.data, ip="127.0.0.1", agentPort=8765))
            self.assertEqual(self.path.read_bytes(), original)
            raise OSError("generated replacement failure")

        with patch.object(fleet.os, "replace", side_effect=fail_replace) as replace:
            with self.assertRaises(fleet.FleetError):
                fleet.atomic_update(room, "127.0.0.1", 8765)
            self.assertEqual(replace.call_count, 1,
                             "Valid BOM input never reached the replacement failure control")
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertTrue(all(not path.exists() for path in temporary_paths))
        self.assert_no_temporary()

    def test_malformed_encoding_is_rejected_before_creating_a_temporary(self):
        ordinary = json.dumps(self.data, ensure_ascii=False).encode("utf-8")
        for payload in (BOM + BOM + ordinary, b" \r\n" + BOM + ordinary,
                        BOM + ordinary.replace(b"PUBLIC-REFRESH-TOKEN", b"\xff", 1)):
            with self.subTest(payload_prefix=payload[:8]):
                self.write_record()
                room = self.load_room()
                self.path.write_bytes(payload)
                with patch.object(fleet.tempfile, "NamedTemporaryFile",
                                  side_effect=AssertionError("Malformed input reached writer")):
                    with self.assertRaises(fleet.FleetError):
                        fleet.atomic_update(room, "127.0.0.1", 8765)
                self.assertEqual(self.path.read_bytes(), payload)
                self.assert_no_temporary()

    def test_embedded_feff_string_data_survives_endpoint_update(self):
        self.data["name"] = "Room\ufeffName"
        self.data["extra"] = {"text": "Value\ufeffEnd"}
        self.write_record(bom=False)
        room = self.load_room()
        self.update_successfully(room)
        self.assert_updated("127.0.0.1", 8765)

    def test_registry_replaced_with_symlink_is_rejected_without_mutation(self):
        self.write_record()
        room = self.load_room()
        owned_target = self.root / "owned-target.json"
        target_bytes = b'{"public":"owned symlink target must remain unchanged"}\n'
        owned_target.write_bytes(target_bytes)
        owned_target.chmod(0o600)
        self.path.unlink()
        self.path.symlink_to(owned_target)
        with self.assertRaises(fleet.FleetError):
            fleet.atomic_update(room, "127.0.0.1", 8765)
        self.assertTrue(self.path.is_symlink())
        self.assertEqual(owned_target.read_bytes(), target_bytes)
        self.assert_no_temporary()


if __name__ == "__main__":
    unittest.main()
