#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Actual CLI admission tests; only generated registries and owned loopback peers."""

import contextlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest


class FleetPortAdmissionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value = os.environ.get("IMMORTAL_FLEET_CLI")
        if not value or not Path(value).is_file():
            raise RuntimeError("Set IMMORTAL_FLEET_CLI to a freshly compiled fleetctl")
        cls.binary = str(Path(value).resolve())

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="public-fleet-port-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.registry = self.root / "registry"
        self.registry.mkdir(mode=0o700)
        self.env = {**os.environ, "IMMORTAL_FLEET_DIR": str(self.registry)}

    def device(self, name="Generated", **fields):
        value = {"name": name, "serial": name, "ip": "127.0.0.1",
                 "token": "PUBLIC-PORT-TEST"}
        value.update(fields)
        target = self.registry / (name + ".json")
        target.write_text(json.dumps(value), encoding="utf-8")
        target.chmod(0o600)

    def run_cli(self, *args):
        return subprocess.run([self.binary, *args], env=self.env, cwd=self.root,
                              capture_output=True, timeout=4)

    def assert_rejected(self, result):
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertTrue(result.stderr.startswith(b"error:"), result.stdout + result.stderr)
        self.assertIn(b"port", result.stderr.lower(), result.stdout + result.stderr)
        self.assertNotIn(b"PUBLIC-PORT-TEST", result.stderr,
                         "Validation diagnostic must not echo the credential")

    @contextlib.contextmanager
    def peer(self, port=0):
        requests, errors = [], []
        stopped = threading.Event()
        listener = socket.socket()
        try:
            # Rebind our own completed default-port fixture despite TCP TIME_WAIT.
            # SO_REUSEPORT is deliberately absent: never share another listener.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                listener.bind(("127.0.0.1", port))
            except OSError as error:
                if port == 8723:
                    self.skipTest("Cannot own the default loopback port: " + str(error.errno))
                raise
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
                            body = b'{"ok":true,"fixture":"owned-port"}'
                            connection.sendall(
                                b"HTTP/1.1 200 Fixture\r\nContent-Length: " +
                                str(len(body)).encode("ascii") +
                                b"\r\nConnection: close\r\n\r\n" + body)
                    except Exception as error:
                        errors.append(error)

            worker = threading.Thread(target=serve, name="owned-fleet-port-peer")
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

    def direct(self, port=None):
        args = ["info", "--host", "127.0.0.1", "--token", "PUBLIC-PORT-TEST"]
        if port is not None:
            args.extend(["--port", str(port)])
        return self.run_cli(*args)

    def assert_info_request(self, request, port):
        self.assertTrue(request.startswith(b"GET /info HTTP/1.1\r\n"), request)
        self.assertIn(("Host: 127.0.0.1:" + str(port) + "\r\n").encode(), request)
        self.assertIn(b"Authorization: Bearer PUBLIC-PORT-TEST\r\n", request)

    def test_invalid_direct_port_never_falls_back_to_owned_default(self):
        # If 8723 cannot be owned, do not contact an unknown local default service.
        with self.peer(8723) as (_, requests):
            for invalid in ("not-a-port", "", "0", "-1", "65536", "999999999999",
                            "1.5", "1e3"):
                with self.subTest(port=invalid):
                    prior = len(requests)
                    result = self.direct(invalid)
                    self.assert_rejected(result)
                    self.assertEqual(len(requests), prior,
                                     "Invalid explicit port must not send to the default")

    def test_omitted_direct_port_still_uses_owned_default(self):
        with self.peer(8723) as (port, requests):
            result = self.direct()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_info_request(requests[0], port)

    def test_registry_overflow_does_not_wrap_to_owned_ephemeral_port(self):
        with self.peer() as (port, requests):
            self.device(agentPort=port + 65536)
            result = self.run_cli("info")
        self.assert_rejected(result)
        self.assertEqual(requests, [], "Overflow must be rejected before any request")

    def test_registry_fraction_does_not_truncate_to_owned_ephemeral_port(self):
        with self.peer() as (port, requests):
            self.device(agentPort=port + 0.75)
            result = self.run_cli("info")
        self.assert_rejected(result)
        self.assertEqual(requests, [], "Fractional port must not select a nearby listener")

    def test_invalid_primary_type_cannot_fall_through_to_valid_legacy_alias(self):
        with self.peer() as (port, requests):
            for invalid in ("8723", True, False, None, [], {}):
                with self.subTest(port=invalid):
                    self.device(agentPort=invalid, port=port)
                    prior = len(requests)
                    result = self.run_cli("info")
                    self.assert_rejected(result)
                    self.assertEqual(len(requests), prior)

    def test_invalid_registry_numeric_ranges_are_rejected_without_network(self):
        for field in ("agentPort", "port"):
            for invalid in (0, -1, 65536, -65536, 1e100, 65535.5):
                with self.subTest(field=field, port=invalid):
                    self.device(**{field: invalid})
                    self.assert_rejected(self.run_cli("devices"))

    def test_invalid_legacy_types_are_rejected_without_network(self):
        for invalid in ("8723", True, False, None, [], {}):
            with self.subTest(port=invalid):
                self.device(port=invalid)
                self.assert_rejected(self.run_cli("devices"))

    def test_invalid_registry_record_blocks_entire_fanout(self):
        with self.peer() as (port, requests):
            self.device("A-valid", agentPort=port)
            self.device("Z-invalid", agentPort=port + 65536)
            result = self.run_cli("info", "--device", "all")
        self.assert_rejected(result)
        self.assertEqual(requests, [], "A bad row must not permit partial commands to good rows")

    def test_omission_alias_and_valid_numeric_boundaries_remain_listable(self):
        cases = (
            ("Default", {}, 8723),
            ("PrimaryMinimum", {"agentPort": 1}, 1),
            ("PrimaryMaximum", {"agentPort": 65535}, 65535),
            ("LegacyMinimum", {"port": 1}, 1),
            ("LegacyMaximum", {"port": 65535}, 65535),
            ("IntegralNumeric", {"agentPort": 43210.0}, 43210),
        )
        for name, fields, expected in cases:
            self.device(name, **fields)
        result = self.run_cli("devices")
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.decode().splitlines()
        self.assertEqual(len(lines), len(cases))
        for name, _, expected in cases:
            line = next(line for line in lines if line.split()[0] == name)
            self.assertIn("127.0.0.1:" + str(expected), line)

    def test_valid_primary_port_takes_priority_over_unused_bad_alias(self):
        with self.peer() as (port, requests):
            self.device(agentPort=port, port="unused-invalid")
            result = self.run_cli("info")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_info_request(requests[0], port)

    def test_legacy_port_alias_and_host_alias_still_make_real_request(self):
        with self.peer() as (port, requests):
            self.device("Mixed-Case", ip=None, host="127.0.0.1", port=port)
            result = self.run_cli("info", "--device", "mixed-case")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_info_request(requests[0], port)

    def test_direct_valid_port_bypasses_unrelated_invalid_registry(self):
        self.device(agentPort="invalid-unselected")
        with self.peer() as (port, requests):
            result = self.direct(port)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(requests), 1)
        self.assert_info_request(requests[0], port)


if __name__ == "__main__":
    unittest.main()
