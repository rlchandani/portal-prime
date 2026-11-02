"""
Copyright (c) 2026 Starbright Lab.

This source code is licensed under the MIT license found in the
LICENSE file in the root directory of this source tree.

Run with IMMORTAL_FLEET_CLI pointing to a freshly compiled fleetctl.
All servers and registry entries are isolated public loopback fixtures.
"""

import contextlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest


class FleetHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value = os.environ.get("IMMORTAL_FLEET_CLI")
        if not value or not Path(value).is_file():
            raise RuntimeError("Set IMMORTAL_FLEET_CLI to a freshly built fleetctl")
        cls.binary = str(Path(value).resolve())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="immortal-fleet-http-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.registry = self.root / "registry"
        self.registry.mkdir()
        self.env = {**os.environ, "IMMORTAL_FLEET_DIR": str(self.registry)}

    @contextlib.contextmanager
    def server(self, response):
        requests, errors = [], []
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(5)

            def serve():
                try:
                    with listener.accept()[0] as connection:
                        connection.settimeout(5)
                        header = b""
                        while b"\r\n\r\n" not in header:
                            chunk = connection.recv(4096)
                            if not chunk:
                                raise AssertionError("Incomplete client request")
                            header += chunk
                        requests.append(header)
                        connection.sendall(response)
                except Exception as error:
                    errors.append(error)

            worker = threading.Thread(target=serve, daemon=True)
            worker.start()
            try:
                yield listener.getsockname()[1], requests
            finally:
                worker.join(6)
                self.assertFalse(worker.is_alive(), "Fixture server did not terminate")
                if errors:
                    raise errors[0]

    def run_cli(self, port, *args):
        return subprocess.run(
            [self.binary, *args, "--host", "127.0.0.1", "--port", str(port),
             "--token", "PUBLIC-TEST"],
            env=self.env, capture_output=True, timeout=5,
        )

    @staticmethod
    def json_reply(body, status=200):
        return (f"HTTP/1.1 {status} Fixture\r\nContent-Length: {len(body)}\r\n\r\n"
                .encode("ascii") + body)

    @contextlib.contextmanager
    def sequence_server(self, replies):
        requests, errors = [], []
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(3)
            listener.settimeout(5)

            def serve():
                try:
                    for status, body in replies:
                        try:
                            connection, _ = listener.accept()
                        except socket.timeout:
                            break  # A failed stage must stop the remaining requests.
                        with connection:
                            connection.settimeout(5)
                            incoming = b""
                            while b"\r\n\r\n" not in incoming:
                                chunk = connection.recv(4096)
                                if not chunk:
                                    raise AssertionError("Incomplete client request")
                                incoming += chunk
                            header, payload = incoming.split(b"\r\n\r\n", 1)
                            length = 0
                            for line in header.split(b"\r\n")[1:]:
                                key, _, value = line.partition(b":")
                                if key.lower() == b"content-length":
                                    length = int(value)
                            while len(payload) < length:
                                chunk = connection.recv(4096)
                                if not chunk:
                                    raise AssertionError("Incomplete client upload")
                                payload += chunk
                            method, path, _ = header.split(b"\r\n", 1)[0].split(b" ")
                            requests.append((method.decode("ascii"), path.decode("ascii"), payload))
                            connection.sendall(self.json_reply(body, status))
                        listener.settimeout(0.3)
                except Exception as error:
                    errors.append(error)

            worker = threading.Thread(target=serve, daemon=True)
            worker.start()
            try:
                yield listener.getsockname()[1], requests
            finally:
                worker.join(6)
                self.assertFalse(worker.is_alive(), "Sequence server did not terminate")
                if errors:
                    raise errors[0]

    def json_commands(self):
        upload = self.root / "public-upload.bin"
        upload.write_bytes(b"PUBLIC UPLOAD")
        return (
            ("info",), ("install", "org.example.public"), ("ls", "/public"),
            ("push", str(upload), "/public/upload.bin"), ("config",),
            ("action", "identify"),
        )

    def test_json_commands_require_literal_unambiguous_success_object(self):
        for command in self.json_commands():
            for body in (
                b'{"ok":false}', b'{}', b'{"ok":1}', b'{"ok":"true"}',
                b'not JSON', b'[]', b'{"ok":true,"ok":false}',
                b'{"ok":false,"ok":true}', b'{"ok":true,"metadata":{"x":1,"x":1}}',
            ):
                with self.subTest(command=command[0], body=body):
                    with self.server(self.json_reply(body)) as (port, requests):
                        result = self.run_cli(port, *command)
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertEqual(len(requests), 1)

    def test_json_commands_accept_literal_success_object(self):
        body = b'{"ok":true,"public":"fixture"}'
        for command in self.json_commands():
            with self.subTest(command=command[0]):
                with self.server(self.json_reply(body)) as (port, _):
                    result = self.run_cli(port, *command)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout), json.loads(body))

    def test_opaque_raw_get_json_remains_http_only(self):
        body = b'{"ok":false,"data":"public opaque value"}'
        with self.server(self.json_reply(body)) as (port, requests):
            result = self.run_cli(port, "raw", "GET", "/public/data")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), json.loads(body))
        self.assertTrue(requests[0].startswith(b"GET /public/data "))

    def test_plaintext_logcat_cat_and_pull_remain_http_only(self):
        body = b"PUBLIC TEXT ok=false\n"
        for command in (("logcat",), ("cat", "/public/text"), ("pull", "/public/text", "-")):
            with self.subTest(command=command[0]):
                with self.server(self.json_reply(body)) as (port, _):
                    result = self.run_cli(port, *command)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, body)

    def test_dev_update_failed_stage_stops_without_later_posts(self):
        apk = self.root / "public-dummy.apk"
        apk.write_bytes(b"PUBLIC DUMMY APK BYTES")
        expected = [("POST", "/dev"), ("POST", "/fs/write?path=%2Fpublic%2Fdummy.apk"),
                    ("POST", "/install")]
        for failed_stage in (0, 1, 2):
            for status, body in ((200, b'{"ok":false}'), (503, b'{"ok":true}')):
                with self.subTest(failed_stage=failed_stage, status=status):
                    replies = [(200, b'{"ok":true}')] * 3
                    replies[failed_stage] = (status, body)
                    with self.sequence_server(replies) as (port, requests):
                        result = self.run_cli(
                            port, "dev", "update", str(apk), "--path", "/public/dummy.apk",
                            "--package", "org.example.public")
                    self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                    self.assertEqual([(method, path) for method, path, _ in requests],
                                     expected[:failed_stage + 1])

    def test_dev_update_success_posts_exact_three_stage_sequence(self):
        apk = self.root / "public-dummy.apk"
        apk.write_bytes(b"PUBLIC DUMMY APK BYTES")
        with self.sequence_server([(200, b'{"ok":true}')] * 3) as (port, requests):
            result = self.run_cli(
                port, "dev", "update", str(apk), "--path", "/public/dummy.apk",
                "--package", "org.example.public")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(method, path) for method, path, _ in requests], [
            ("POST", "/dev"), ("POST", "/fs/write?path=%2Fpublic%2Fdummy.apk"),
            ("POST", "/install"),
        ])
        self.assertEqual(json.loads(requests[0][2]), {"enabled": True})
        self.assertEqual(requests[1][2], apk.read_bytes())
        self.assertEqual(json.loads(requests[2][2]),
                         {"path": "/public/dummy.apk", "packageName": "org.example.public"})

    def test_complete_info_returns_success_and_authenticates(self):
        body = b'{"ok":true,"name":"Public fixture"}'
        reply = b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\n\r\n" % len(body) + body
        with self.server(reply) as (port, requests):
            result = self.run_cli(port, "info")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), json.loads(body))
        self.assertIn(b"Authorization: Bearer PUBLIC-TEST\r\n", requests[0])

    def test_incomplete_info_returns_failure_without_partial_json(self):
        with self.server(b'HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\n{"ok":') as (port, _):
            result = self.run_cli(port, "info")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b'{"ok":', result.stdout)
        self.assertIn(b"transport error", result.stderr)

    def test_binary_download_preserves_every_byte(self):
        body = b"\x00\xff\r\nCaf\xc3\xa9"
        reply = b"HTTP/1.1 200 OK\r\ncontent-length: %d\r\n\r\n" % len(body) + body
        destination = self.root / "complete.bin"
        with self.server(reply) as (port, requests):
            result = self.run_cli(port, "pull", "/public fixture.bin", str(destination))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(destination.read_bytes(), body)
        self.assertTrue(requests[0].startswith(b"GET /fs/read?path=%2Fpublic%20fixture.bin "))

    def test_interrupted_download_preserves_existing_file(self):
        destination = self.root / "existing.bin"
        destination.write_bytes(b"ORIGINAL")
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/public.bin", str(destination))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(destination.read_bytes(), b"ORIGINAL")
        self.assertNotIn(b"wrote", result.stdout)

    def test_interrupted_download_does_not_create_a_file(self):
        destination = self.root / "absent.bin"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/public.bin", str(destination))
        self.assertEqual(result.returncode, 1)
        self.assertFalse(destination.exists())

    def test_failed_stdout_download_emits_no_partial_file(self):
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/public.bin", "-")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, b"")

    def test_unknown_length_stream_download_uses_clean_close(self):
        body = b"\x00\xffPUBLIC STREAM"
        destination = self.root / "stream.bin"
        with self.server(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + body) as (port, _):
            result = self.run_cli(port, "pull", "/public.bin", str(destination))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(destination.read_bytes(), body)

    def test_procfs_zero_length_download_uses_clean_close(self):
        body = b"PUBLIC PROCFS CONTENT\n"
        destination = self.root / "maps.txt"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" + body) as (port, _):
            result = self.run_cli(port, "pull", "/proc/self/maps", str(destination))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(destination.read_bytes(), body)

    def test_procfs_zero_length_cat_preserves_text(self):
        body = b"PUBLIC PROCFS CONTENT\n"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" + body) as (port, _):
            result = self.run_cli(port, "cat", "/proc/self/maps")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, body)

    def test_regular_zero_length_file_rejects_extra_body(self):
        destination = self.root / "empty.bin"
        destination.write_bytes(b"ORIGINAL")
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/sdcard/public.bin", str(destination))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(destination.read_bytes(), b"ORIGINAL")

    def test_proc_parent_traversal_does_not_relax_file_length(self):
        destination = self.root / "empty.bin"
        destination.write_bytes(b"ORIGINAL")
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/proc/../sdcard/public.bin", str(destination))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(destination.read_bytes(), b"ORIGINAL")

    def test_procfs_positive_length_still_rejects_short_body(self):
        destination = self.root / "absent.txt"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "pull", "/proc/self/maps", str(destination))
        self.assertEqual(result.returncode, 1)
        self.assertFalse(destination.exists())

    def test_raw_procfs_query_preserves_compatibility(self):
        body = b"PUBLIC PROCFS CONTENT\n"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" + body) as (port, _):
            result = self.run_cli(port, "raw", "GET", "/fs/read?path=%2Fproc%2Fself%2Fmaps")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, body)

    def test_raw_procfs_preserves_numeric_leading_maps(self):
        body = b"12c00000-12c01000 r-xp /public.bin\n"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" + body) as (port, _):
            result = self.run_cli(port, "raw", "GET", "/fs/read?path=%2Fproc%2Fself%2Fmaps")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, body)

    def test_raw_duplicate_path_uses_first_value(self):
        route = "/fs/read?path=%2Fsdcard%2Fpublic.bin&path=%2Fproc%2Fself%2Fmaps"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "raw", "GET", route)
        self.assertEqual(result.returncode, 1)

    def test_raw_encoded_parent_traversal_keeps_strict_length(self):
        route = "/fs/read?path=%2Fproc%2F..%2Fsdcard%2Fpublic.bin"
        with self.server(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nabc") as (port, _):
            result = self.run_cli(port, "raw", "GET", route)
        self.assertEqual(result.returncode, 1)

    def test_fanout_returns_failure_when_one_room_response_is_incomplete(self):
        good = b'HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{"ok":true}'
        bad = b'HTTP/1.1 200 OK\r\nContent-Length: 30\r\n\r\n{"ok":'
        with self.server(good) as (good_port, _), self.server(bad) as (bad_port, _):
            for name, port in [("Public A", good_port), ("Public B", bad_port)]:
                self.registry.joinpath(name + ".json").write_text(json.dumps({
                    "name": name, "serial": name, "ip": "127.0.0.1",
                    "agentPort": port, "token": "PUBLIC-TEST",
                }))
            result = subprocess.run(
                [self.binary, "info", "--device", "all"], env=self.env,
                capture_output=True, timeout=5,
            )
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"Public A", result.stdout)
        self.assertIn(b"Public B", result.stdout)
        self.assertIn(b'"ok": true', result.stdout)
        self.assertNotIn(b'{"ok":', result.stdout)


if __name__ == "__main__":
    unittest.main()
