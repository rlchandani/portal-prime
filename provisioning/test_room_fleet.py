#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline regression checks: all discovery, HTTP, and CLI calls are faked."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import hashlib
import http.client
import io
import json
from pathlib import Path
import socket
import stat
import subprocess
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch


HELPER = Path(__file__).with_name("room-fleet.py")
fleet = None
if HELPER.exists():
    spec = importlib.util.spec_from_file_location("room_fleet", HELPER)
    fleet = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fleet)


class RoomFleetTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(fleet, "room-fleet.py has not been implemented")
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.registry = Path(self.directory.name).resolve()
        self.data = {
            "serial": "family-serial", "name": "Family Area", "ip": "192.0.2.10",
            "agentPort": 8723, "token": "fake-family-secret", "model": "Portal",
            "extra": {"keep": True},
        }
        self.path = self.write_room(self.data)
        self.rooms = fleet.load_rooms(self.registry)

    def write_room(self, data):
        path = self.registry / (data["serial"] + ".json")
        path.write_text(json.dumps(data), encoding="utf-8")
        path.chmod(0o600)
        return path

    def test_lookup_accepts_only_exact_registered_service_and_local_host(self):
        output = (
            "12:00:00.123 Family Area 2._immortal-remote._tcp.local. "
            "can be reached at other.local.:8723 (interface 4)\n"
            "12:00:00.124 Family Area._wrong._tcp.local. "
            "can be reached at other.local.:8723 (interface 4)\n"
            "12:00:00.125 Family Area._immortal-remote._tcp.local. "
            "can be reached at portal.local.:8724 (interface 4)\n"
            "12:00:00.126 Family Area._immortal-remote._tcp.local. "
            "can be reached at portal.local.:8724 (interface 5)\n"
            "12:00:00.127 Family Area._immortal-remote._tcp.local. "
            "can be reached at example.com.:8723 (interface 4)\n"
            "12:00:00.128 Family Area._immortal-remote._tcp.local. "
            "can be reached at invalid.local.:70000 (interface 4)\n"
        )
        self.assertEqual(
            fleet.parse_lookup(output, "Family Area"), [("portal.local", 8724)]
        )

    def test_lookup_decodes_real_dns_sd_escaped_room_names(self):
        output = (
            r"21:39:17.152  Family\032Area._immortal-remote._tcp.local. "
            "can be reached at Android.local.:8723 (interface 17) Flags: 1\n"
            r"21:39:18.152  Living\032Area._immortal-remote._tcp.local. "
            "can be reached at Android.local.:8723 (interface 17) Flags: 1\n"
        )
        for name in ("Family Area", "Living Area"):
            with self.subTest(name=name):
                self.assertEqual(fleet.parse_lookup(output, name), [("android.local", 8723)])

    def test_lookup_escaped_lookalikes_and_nonlocal_hosts_do_not_match(self):
        for service, host in (
            (r"Family\032Area\0322._immortal-remote._tcp.local.", "Android.local."),
            (r"Other\032Family\032Area._immortal-remote._tcp.local.", "Android.local."),
            (r"family\032Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\032\032Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\\032Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\092032Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\194\160Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\032Area\046_immortal-remote._tcp.local.", "Android.local."),
            (r"Family\032Area._immortal-remote._tcp.local.evil.", "Android.local."),
            (r"Family\032Area._immortal-remote._tcp.local.", "Android.local.evil."),
            (r"Family\032Area._immortal-remote._tcp.local.", "example.com."),
            (r"Family\32Area._immortal-remote._tcp.local.", "Android.local."),
            (r"Family\999Area._immortal-remote._tcp.local.", "Android.local."),
        ):
            with self.subTest(service=service, host=host):
                output = (
                    f"21:39:17.152  {service} can be reached at {host}:8723 "
                    "(interface 17) Flags: 1\n"
                )
                self.assertEqual(fleet.parse_lookup(output, "Family Area"), [])

    def test_lookup_preserves_escaped_dot_and_backslash_as_room_label_characters(self):
        output = (
            r"21:39:17.152  Family\.Area\\Wall._immortal-remote._tcp.local. "
            "can be reached at Android.local.:8723 (interface 17) Flags: 1\n"
        )
        self.assertEqual(
            fleet.parse_lookup(output, r"Family.Area\Wall"), [("android.local", 8723)]
        )
        self.assertEqual(fleet.parse_lookup(output, "Family"), [])

    def test_lookup_decodes_decimal_utf8_octets_without_accepting_invalid_bytes(self):
        output = (
            r"21:39:17.152  Caf\195\169\032Area._immortal-remote._tcp.local. "
            "can be reached at Android.local.:8723 (interface 17) Flags: 1\n"
            r"21:39:17.153  Caf\255\032Area._immortal-remote._tcp.local. "
            "can be reached at invalid.local.:8723 (interface 17) Flags: 1\n"
        )
        self.assertEqual(
            fleet.parse_lookup(output, "Café Area"), [("android.local", 8723)]
        )

    def test_lookup_uses_partial_output_when_dns_sd_reaches_timeout(self):
        output = (
            "12:00:00.125 Family Area._immortal-remote._tcp.local. "
            "can be reached at portal.local.:8724 (interface 4)\n"
        ).encode()

        def fake_run(args, **kwargs):
            self.assertEqual(
                args, ["/usr/bin/dns-sd", "-L", "Family Area",
                       "_immortal-remote._tcp", "local"]
            )
            self.assertEqual(kwargs["timeout"], 0.2)
            raise subprocess.TimeoutExpired(args, 0.2, output=output)

        with patch.object(fleet.subprocess, "run", side_effect=fake_run):
            self.assertEqual(fleet.lookup("Family Area", 0.2), ("portal.local", 8724))

    def test_lookup_rejects_conflicting_exact_names(self):
        output = "\n".join(
            "12:00:00.125 Family Area._immortal-remote._tcp.local. "
            f"can be reached at {host}.local.:8723 (interface 4)"
            for host in ("first", "second")
        )
        completed = subprocess.CompletedProcess([], 0, output, "")
        with patch.object(fleet.subprocess, "run", return_value=completed):
            with self.assertRaises(fleet.FleetError):
                fleet.lookup("Family Area", 0.2)

    def test_json_blocks_do_not_treat_nested_failure_as_top_level(self):
        output = (
            "\n=== Family Area / family-serial (http://192.0.2.10:8723) ===\n"
            'pushing /tmp/app{draft}.apk (42 bytes) -> /sdcard/app.apk\n'
            'installing com.immortal.launcher ...\n'
            '{\n  "ok": true,\n  "details": {"ok": false, "text": "brace }"}\n}\n'
            '=== Living Area / living-serial (http://192.0.2.11:8723) ===\n'
            '{"ok": false, "error": "signature_mismatch"}\n'
        )
        self.assertEqual(
            list(fleet.json_blocks(output)),
            [{"ok": True, "details": {"ok": False, "text": "brace }"}},
             {"ok": False, "error": "signature_mismatch"}],
        )

    def test_atomic_update_changes_only_endpoint_and_keeps_private_mode(self):
        original_inode = self.path.stat().st_ino
        fleet.atomic_update(self.rooms[0], "192.0.2.20", 8724)
        expected = dict(self.data, ip="192.0.2.20", agentPort=8724)
        self.assertEqual(json.loads(self.path.read_text()), expected)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertNotEqual(self.path.stat().st_ino, original_inode)
        self.assertEqual(list(self.registry.iterdir()), [self.path])

    def test_failed_replace_keeps_original_and_removes_private_temporary_file(self):
        original = self.path.read_bytes()

        def fail_replace(source, destination):
            self.assertEqual(Path(source).parent, self.registry)
            self.assertEqual(stat.S_IMODE(Path(source).stat().st_mode), 0o600)
            self.assertEqual(Path(destination), self.path)
            self.assertEqual(self.path.read_bytes(), original)
            raise OSError("replace failed")

        with patch.object(fleet.os, "replace", side_effect=fail_replace):
            with self.assertRaises(fleet.FleetError):
                fleet.atomic_update(self.rooms[0], "192.0.2.20", 8724)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(list(self.registry.iterdir()), [self.path])

    def test_atomic_update_rejects_registry_changed_since_authentication(self):
        modified = dict(self.data, token="fake-rotated-secret")
        self.path.write_text(json.dumps(modified))
        with self.assertRaises(fleet.FleetError):
            fleet.atomic_update(self.rooms[0], "192.0.2.20", 8724)
        self.assertEqual(json.loads(self.path.read_text()), modified)

    def fake_http(self, status, body):
        room = self.rooms[0]

        class Response:
            def read(self, limit):
                return body

        Response.status = status
        response = Response()

        class Connection:
            def __init__(connection, ip, port, timeout):
                self.assertEqual((ip, port, timeout), ("192.0.2.20", 8724, 0.2))

            def request(connection, method, path, headers):
                self.assertEqual((method, path), ("GET", "/info"))
                self.assertEqual(headers, {"Authorization": "Bearer " + room.data["token"]})

            def getresponse(connection):
                return response

            def close(connection):
                pass

        return Connection

    def test_authenticated_info_requires_exact_name_and_success_without_echoing_body(self):
        for status, body in (
            (401, b'Authorization: Bearer fake-family-secret'),
            (200, b'{"ok":true,"name":"Family Area 2","token":"fake-family-secret"}'),
            (200, b'{"ok":false,"name":"Family Area"}'),
            (200, b'not JSON fake-family-secret'),
        ):
            with self.subTest(status=status, body=body):
                with patch.object(fleet.http.client, "HTTPConnection",
                                  self.fake_http(status, body)):
                    with self.assertRaises(fleet.FleetError) as error:
                        fleet.verify_info(self.rooms[0], "192.0.2.20", 8724, 0.2)
                self.assertNotIn("fake-family-secret", str(error.exception))
                self.assertNotIn("Authorization", str(error.exception))
        with patch.object(fleet.http.client, "HTTPConnection",
                          self.fake_http(200, b'{"ok":true,"name":"Family Area"}')):
            fleet.verify_info(self.rooms[0], "192.0.2.20", 8724, 0.2)

    def test_refresh_updates_one_room_and_reports_other_down_without_aborting(self):
        other = dict(self.data, serial="living-serial", name="Living Area",
                     ip="192.0.2.11", token="fake-living-secret")
        other_path = self.write_room(other)
        rooms = fleet.load_rooms(self.registry)

        def fake_lookup(name, timeout):
            if name == "Living Area":
                raise fleet.FleetError("service lookup timed out")
            return "portal.local", 8724

        addresses = [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                      ("192.0.2.20", 8724))]
        output = io.StringIO()
        with patch.object(fleet, "lookup", side_effect=fake_lookup), \
                patch.object(fleet.socket, "getaddrinfo", return_value=addresses), \
                patch.object(fleet.http.client, "HTTPConnection",
                             self.fake_http(200, b'{"ok":true,"name":"Family Area"}')), \
                redirect_stdout(output):
            result = fleet.report_rooms(rooms, 0.2, refresh=True)
        self.assertEqual(result, 1)
        self.assertIn("Family Area: reachable", output.getvalue())
        self.assertIn("Living Area: down", output.getvalue())
        self.assertNotIn("secret", output.getvalue())
        self.assertEqual(json.loads(self.path.read_text())["ip"], "192.0.2.20")
        self.assertEqual(json.loads(other_path.read_text()), other)

    def test_refresh_identity_mismatch_does_not_modify_registry(self):
        original = self.path.read_bytes()
        addresses = [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                      ("192.0.2.20", 8724))]
        with patch.object(fleet, "lookup", return_value=("portal.local", 8724)), \
                patch.object(fleet.socket, "getaddrinfo", return_value=addresses), \
                patch.object(fleet.http.client, "HTTPConnection",
                             self.fake_http(200, b'{"ok":true,"name":"Living Area"}')), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(fleet.report_rooms(self.rooms, 0.2, refresh=True), 1)
        self.assertEqual(self.path.read_bytes(), original)

    def forward(self, args, stdout, stderr="", returncode=0):
        captured_out, captured_err = io.StringIO(), io.StringIO()

        def fake_run(command, **kwargs):
            self.assertEqual(command[:1], ["/fake/fleetctl"])
            self.assertIn("--device", command)
            self.assertEqual(kwargs["env"]["IMMORTAL_FLEET_DIR"], str(self.registry))
            return subprocess.CompletedProcess(command, returncode, stdout, stderr)

        with patch.object(fleet.subprocess, "run", side_effect=fake_run), \
                redirect_stdout(captured_out), redirect_stderr(captured_err):
            result = fleet.forward("/fake/fleetctl", self.registry, self.rooms, args)
        return result, captured_out.getvalue(), captured_err.getvalue()

    def test_group_dev_update_rejects_http_200_application_failure(self):
        self.write_room(dict(self.data, serial="living-serial", name="Living Area",
                             token="fake-living-secret"))
        self.rooms = fleet.load_rooms(self.registry)
        output = (
            '=== Family Area ===\n{"ok":true}\n'
            '=== Living Area ===\n{"ok":false,"error":"signature_mismatch"}\n'
        )
        result, stdout, stderr = self.forward(["dev", "update", "/tmp/app.apk"], output)
        self.assertEqual(result, 1)
        self.assertEqual(stdout, output)
        self.assertIn("ok:false", stderr)

    def test_dev_update_detects_action_with_interspersed_device_flag(self):
        result, _, _ = self.forward(
            ["dev", "--device", "Family Area", "update", "/tmp/app.apk"],
            '{"ok":false}\n',
        )
        self.assertEqual(result, 1)

    def test_dev_update_requires_json_success_for_each_target(self):
        self.write_room(dict(self.data, serial="living-serial", name="Living Area",
                             token="fake-living-secret"))
        self.rooms = fleet.load_rooms(self.registry)
        for output in ('installing ...\n', '{"ok":true}\n', '{"ok": tru\n'):
            with self.subTest(output=output):
                result, _, _ = self.forward(["dev", "update", "/tmp/app.apk"], output)
                self.assertEqual(result, 1)
        result, _, _ = self.forward(
            ["dev", "update", "/tmp/app.apk"],
            '{"ok":true,"details":{"ok":false}}\n{"ok":true}\n',
        )
        self.assertEqual(result, 0)

    def test_forward_redacts_secrets_and_preserves_native_failure(self):
        result, stdout, stderr = self.forward(
            ["info"], '{"ok":true,"token":"unrecognized-secret"}\nfake-family-secret\n',
            "Authorization: Bearer another-secret\ntransport failed fake-family-secret\n", 2,
        )
        self.assertEqual(result, 2)
        self.assertNotIn("unrecognized-secret", stdout)
        self.assertNotIn("fake-family-secret", stdout + stderr)
        self.assertNotIn("another-secret", stderr)
        self.assertNotIn("Authorization", stderr)
        self.assertIn('"ok":true', stdout)
        self.assertIn("transport failed", stderr)

    def test_forward_group_target_and_rejects_credentials_before_running_cli(self):
        def fake_run(command, **kwargs):
            self.assertEqual(command, ["/fake/fleetctl", "info", "--device", "all"])
            return subprocess.CompletedProcess(command, 0, "", "")

        with patch.object(fleet.subprocess, "run", side_effect=fake_run):
            fleet.forward("/fake/fleetctl", self.registry, self.rooms, ["info"])
        for args in (["info", "--token", "fake-do-not-echo"],
                     ["info", "--token=fake-do-not-echo"],
                     ["info", "--host", "192.0.2.99"],
                     ["info", "--port=8724"],
                     ["info", "--device", "Unknown Area"]):
            with self.subTest(args=args):
                with patch.object(fleet.subprocess, "run") as run:
                    with self.assertRaises(fleet.FleetError) as error:
                        fleet.forward("/fake/fleetctl", self.registry, self.rooms, args)
                    self.assertNotIn("fake-do-not-echo", str(error.exception))
                    run.assert_not_called()

    def test_parser_uses_environment_paths_and_explicit_overrides(self):
        for explicit in (False, True):
            with self.subTest(explicit=explicit):
                cli = "/fake/flag-fleetctl" if explicit else "/fake/env-fleetctl"
                environment = {
                    "IMMORTAL_FLEET_CLI": "/fake/env-fleetctl",
                    "IMMORTAL_FLEET_DIR": str(self.registry / "missing")
                    if explicit else str(self.registry),
                }
                args = ["--cli", cli, "--registry", str(self.registry)] if explicit else []

                def fake_run(command, **kwargs):
                    self.assertEqual(command, [cli, "info", "--device", "all"])
                    self.assertEqual(kwargs["env"]["IMMORTAL_FLEET_DIR"], str(self.registry))
                    return subprocess.CompletedProcess(command, 0, '{"ok":true}\n', "")

                with patch.dict(fleet.os.environ, environment), \
                        patch.object(fleet.subprocess, "run", side_effect=fake_run), \
                        redirect_stdout(io.StringIO()):
                    self.assertEqual(fleet.main([*args, "info"]), 0)

    def test_default_root_finds_primary_repo_from_source_and_published_copy(self):
        root = self.registry / "primary"
        (root / "provisioning").mkdir(parents=True)
        (root / "provisioning/fleet.rs").write_text("// fake repository marker\n")
        for relative in ("provisioning/room-fleet.py", "build/fleet/room-fleet.py"):
            with self.subTest(relative=relative):
                helper = root / relative
                helper.parent.mkdir(parents=True, exist_ok=True)
                helper.write_text(HELPER.read_text())
                spec = importlib.util.spec_from_file_location("published_room_fleet", helper)
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                self.assertEqual(module.ROOT, root)

                def load(registry):
                    self.assertEqual(registry, root / "provisioning/fleet")
                    return self.rooms

                def run(command, **kwargs):
                    self.assertEqual(command, [str(root / "build/fleet/fleetctl"),
                                               "info", "--device", "all"])
                    self.assertEqual(kwargs["env"]["IMMORTAL_FLEET_DIR"],
                                     str(root / "provisioning/fleet"))
                    return subprocess.CompletedProcess(command, 0, "", "")

                with patch.dict(module.os.environ, {}, clear=True), \
                        patch.object(module, "load_rooms", side_effect=load), \
                        patch.object(module.subprocess, "run", side_effect=run):
                    self.assertEqual(module.main(["info"]), 0)

    def test_helper_help_does_not_require_registry_or_cli(self):
        for command in ([], ["refresh"], ["status"], ["deploy"]):
            with self.subTest(command=command):
                output = io.StringIO()
                with patch.object(fleet, "load_rooms",
                                  side_effect=fleet.FleetError("registry unavailable")), \
                        redirect_stdout(output), redirect_stderr(io.StringIO()):
                    try:
                        result = fleet.main([*command, "--help"])
                    except SystemExit as error:
                        result = error.code
                self.assertEqual(result, 0)
                self.assertIn("--help", output.getvalue())

    def deploy_fixture(self, before, after, remote=None, returncode=1, stdout=None,
                       info_losses=0, failed_room=None, maps_override=None, bad_listing=False,
                       hash_elapsed=0):
        """Fake only HTTP, CLI, and time; run real ownership, hashing, and deployment."""
        package = "com.immortal.launcher.debug"
        apk = self.registry / "upload.apk"
        payload = b"same-version APK fixture"
        apk.write_bytes(payload)
        output, errors = io.StringIO(), io.StringIO()
        observed = {"time": 0.0, "commands": [], "reads": [], "timeouts": [], "paths": []}
        installed = set()
        losses = [info_losses]
        case = self

        class Response:
            def __init__(response, body, proc=False):
                response.status = 200
                response.fp = io.BytesIO(body)
                response.proc = proc

            def read(response, limit):
                # Match the real procfs Content-Length:0 peculiarity.
                return b"" if response.proc else response.fp.read(limit)

            def read1(response, limit):
                observed["reads"].append(limit)
                observed["time"] += hash_elapsed
                return response.fp.read(limit)

            def close(response):
                response.fp.close()

        class Connection:
            def __init__(connection, ip, port, timeout):
                connection.room = next(room for room in case.rooms if room.data["ip"] == ip)
                connection.sock = connection
                observed["timeouts"].append(timeout)

            def settimeout(connection, timeout):
                observed["timeouts"].append(timeout)

            def request(connection, method, path, headers):
                case.assertEqual(method, "GET")
                case.assertEqual(headers["Authorization"],
                                 "Bearer " + connection.room.data["token"])
                connection.path = path
                observed["paths"].append(path)

            def getresponse(connection):
                name = connection.room.data["name"]
                changed = name in installed
                artifact = after if changed else before
                route = urlsplit(connection.path)
                if route.path == "/info":
                    if changed and losses[0]:
                        losses[0] -= 1
                        raise http.client.RemoteDisconnected("fake-family-secret")
                    return Response(json.dumps({"ok": True, "name": name}).encode())
                path = parse_qs(route.query)["path"][0]
                if path == "/proc/self/maps":
                    if maps_override is not None:
                        body = maps_override
                    elif artifact is None:
                        body = b"1000-2000 r--p 0000 00:00 1 /data/app/other.app-x/base.apk\n"
                    else:
                        body = (f"1000-2000 r--p 0000 00:00 1 {artifact[0]}\n" * 2).encode()
                    return Response(body, proc=True)
                case.assertIsNotNone(artifact)
                if route.path == "/fs/list":
                    case.assertEqual(path, artifact[0].rsplit("/", 1)[0])
                    body = b"not JSON fake-family-secret" if bad_listing else json.dumps({
                        "ok": True, "path": path,
                        "entries": [{"name": "base.apk", "type": "file",
                                     "size": len(payload), "mtime": artifact[1]}],
                    }).encode()
                    return Response(body)
                case.assertEqual((route.path, path), ("/fs/read", artifact[0]))
                return Response(payload if remote is None else remote)

            def close(connection):
                pass

        def run(command, **kwargs):
            name = command[-1]
            case.assertEqual(command, [
                "/fake/fleetctl", "dev", "update", str(apk), "--package", package,
                "--device", name,
            ])
            case.assertEqual(kwargs["env"]["IMMORTAL_FLEET_DIR"], str(self.registry))
            observed["commands"].append(name)
            installed.add(name)
            if name == failed_room:
                return subprocess.CompletedProcess(command, 0, '{"ok":false}\n', "")
            text = stdout if stdout is not None else f"installing {package} ...\n\n"
            return subprocess.CompletedProcess(
                command, returncode, text, "(transport error)\n" if returncode == 1 else ""
            )

        def sleep(seconds):
            observed["time"] += seconds

        with patch.object(fleet.http.client, "HTTPConnection", Connection), \
                patch.object(fleet.subprocess, "run", side_effect=run), \
                patch.object(fleet.time, "monotonic", side_effect=lambda: observed["time"]), \
                patch.object(fleet.time, "sleep", side_effect=sleep), \
                redirect_stdout(output), redirect_stderr(errors):
            result = fleet.deploy("/fake/fleetctl", self.registry, self.rooms, apk, package)
        return result, output.getvalue() + errors.getvalue(), observed

    def test_hash_stream_finishes_when_content_length_closes_the_socket(self):
        payload = b"complete installed APK"

        class ClosedSocket:
            closed = False

            def settimeout(self, seconds):
                if self.closed:
                    raise OSError("Bad file descriptor")

        sock = ClosedSocket()

        class Response:
            status = 200
            length = len(payload)

            def read1(self, maximum):
                self.length = 0
                sock.closed = True
                return payload

            def close(self):
                pass

        class Connection:
            def __init__(self, *args, **kwargs):
                self.sock = sock

            def request(self, *args, **kwargs):
                pass

            def getresponse(self):
                return Response()

            def close(self):
                pass

        with patch.object(fleet.http.client, "HTTPConnection", Connection):
            self.assertEqual(
                fleet.deploy_get(self.rooms[0], "/own-apk", len(payload) + 1, 30, hashing=True),
                (len(payload), hashlib.sha256(payload).hexdigest()),
            )

    def test_deploy_verifies_replacement_after_connection_loss_even_same_version(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 100)
        result, output, observed = self.deploy_fixture(before, after, info_losses=1)
        self.assertEqual(result, 0)
        self.assertIn("verified", output)
        self.assertNotIn("transport error", output)
        self.assertNotIn("secret", output)
        self.assertIn("Family Area", output)
        self.assertGreater(observed["time"], 0)
        self.assertLessEqual(observed["time"], 90)

    def test_deploy_accepts_mtime_change_but_rejects_unchanged_artifact(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        result, _, _ = self.deploy_fixture(before, (before[0], 101))
        self.assertEqual(result, 0)
        result, output, observed = self.deploy_fixture(before, before)
        self.assertEqual(result, 1)
        self.assertIn("failed", output)
        self.assertLessEqual(observed["time"], 90)
        self.assertFalse(observed["reads"], "unchanged APK must not be accepted by hash alone")

    def test_deploy_rejects_mismatched_or_oversized_installed_apk(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        for remote in (b"different APK fixture!!", b"same-version APK fixture" + b"extra"):
            with self.subTest(remote=remote):
                result, output, observed = self.deploy_fixture(before, after, remote=remote)
                self.assertEqual(result, 1)
                self.assertIn("failed", output)
                self.assertTrue(all(limit <= len(b"same-version APK fixture") + 1
                                    for limit in observed["reads"]))
                self.assertTrue(all(0 < timeout <= 30 for timeout in observed["timeouts"]))

    def test_deploy_explicit_rejection_or_malformed_result_never_recovers(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        for code, text in (
            (0, '{"ok":false,"result":"failed"}\n'),
            (0, '{"ok":false}\n{"ok":true,"result":"installed",'
                '"packageName":"com.immortal.launcher.debug"}\n'),
            (0, '{"ok": tru\n'),
            (0, '{"ok":true}\n'),
            (0, "garbage\n"),
            (0, "installing com.immortal.launcher.debug ...\n"),
            (1, "installing com.immortal.launcher.debug ...\ngarbage\n"),
        ):
            with self.subTest(code=code, text=text):
                result, output, observed = self.deploy_fixture(
                    before, after, returncode=code, stdout=text,
                )
                self.assertEqual(result, 1)
                self.assertIn("failed", output)
                self.assertFalse(observed["reads"])

    def test_deploy_nonself_requires_actual_package_installed_json(self):
        for code, text, expected in (
            (1, None, 1),
            (0, '{"ok":true}\n', 1),
            (0, '{"ok":true,"result":"installed","packageName":"other.app"}\n', 1),
            (0, '{"ok":true,"result":"installed",'
                '"packageName":"com.immortal.launcher.debug"}\n', 0),
        ):
            with self.subTest(code=code, text=text):
                result, _, observed = self.deploy_fixture(None, None, returncode=code, stdout=text)
                self.assertEqual(result, expected)
                self.assertFalse(observed["reads"])
                self.assertFalse(any(path.startswith("/fs/list") for path in observed["paths"]))

    def test_deploy_continues_after_one_room_fails(self):
        self.write_room(dict(self.data, serial="living-serial", name="Living Area",
                             ip="192.0.2.11", token="fake-living-secret"))
        self.rooms = fleet.load_rooms(self.registry)
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        result, output, observed = self.deploy_fixture(before, after, failed_room="Family Area")
        self.assertEqual(result, 1)
        self.assertEqual(observed["commands"], ["Family Area", "Living Area"])
        self.assertIn("Family Area: deploy failed", output)
        self.assertIn("Living Area: deployed", output)

    def test_deploy_recovery_timeout_is_bounded(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        result, output, observed = self.deploy_fixture(before, after, info_losses=1000)
        self.assertEqual(result, 1)
        self.assertIn("timed out", output)
        self.assertLessEqual(observed["time"], 90)

    def test_deploy_rejects_hash_stream_exceeding_30_second_deadline(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        result, output, observed = self.deploy_fixture(before, after, hash_elapsed=31)
        self.assertEqual(result, 1)
        self.assertIn("timed out", output)
        self.assertEqual(observed["time"], 31)

    def test_deploy_limits_maps_and_rejects_malformed_listing_before_install(self):
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        for options in ({"maps_override": b"x" * (4 * 1024 * 1024)}, {"bad_listing": True}):
            with self.subTest(options=list(options)):
                result, output, observed = self.deploy_fixture(before, before, **options)
                self.assertEqual(result, 1)
                self.assertFalse(observed["commands"])
                self.assertNotIn("secret", output)
                self.assertTrue(all(timeout <= 20 for timeout in observed["timeouts"]))

    def test_deploy_maps_matches_only_exact_package_and_deduplicates_own_path(self):
        maps = (
            "1000-2000 r--p 0000 00:00 1 /data/app/comXimmortalXlauncherXdebug-x/base.apk\n"
            "1000-2000 r--p 0000 00:00 1 /data/app/com.immortal.launcher.debug.extra-x/base.apk\n"
            "1000-2000 r--p 0000 00:00 1 /data/app/other.app-x/base.apk\n"
        ).encode()
        result, _, observed = self.deploy_fixture(None, None, maps_override=maps)
        self.assertEqual(result, 1)
        self.assertFalse(any(path.startswith("/fs/list") for path in observed["paths"]))
        before = ("/data/app/com.immortal.launcher.debug-old/base.apk", 100)
        after = ("/data/app/com.immortal.launcher.debug-new/base.apk", 101)
        result, _, _ = self.deploy_fixture(before, after)
        self.assertEqual(result, 0)


if __name__ == "__main__":
    unittest.main()
