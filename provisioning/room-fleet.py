#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Explicit discovery and native fleetctl forwarding for already-registered rooms.

No provisioning, browsing, implicit refresh, or background work. Tokens stay in
the existing registry; the only registry writes change ip and agentPort.
"""

import argparse
from dataclasses import dataclass, field
import hashlib
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlencode


SERVICE = "_immortal-remote._tcp"
ROOT = next(
    (parent for parent in Path(__file__).resolve().parents
     if (parent / "provisioning/fleet.rs").is_file()),
    Path(__file__).resolve().parents[1],
)
VALUELESS = {"--all", "--check", "--help", "--no-pause", "-h"}


class FleetError(Exception):
    """A safe, locally generated message, never a raw subprocess/HTTP exception."""


class Retryable(FleetError):
    """A connection interruption or replacement still in progress."""


@dataclass
class Room:
    path: Path
    data: dict = field(repr=False)


def load_rooms(registry):
    rooms = []
    try:
        if not registry.is_dir():
            raise FleetError("registry directory is missing")
        for path in sorted(registry.glob("*.json")):
            if path.is_symlink():
                raise FleetError("registry entries must be regular files")
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise FleetError("invalid registry entry")
            for key in ("serial", "name", "token"):
                value = data.get(key)
                if not isinstance(value, str) or not value or "\r" in value or "\n" in value:
                    raise FleetError("registry entry needs serial, name, and existing token")
            if not isinstance(data.get("ip"), str):
                raise FleetError("registry entry needs an IP")
            port = data.get("agentPort")
            if type(port) is not int or not 1 <= port <= 65535:
                raise FleetError("invalid registry agent port")
            rooms.append(Room(path, data))
    except (OSError, ValueError):
        raise FleetError("cannot read registry JSON") from None
    if not rooms:
        raise FleetError("no registered rooms")
    if len({room.data["name"].casefold() for room in rooms}) != len(rooms):
        raise FleetError("conflicting room names in registry")
    if len({room.data["serial"] for room in rooms}) != len(rooms):
        raise FleetError("conflicting serials in registry")
    return sorted(rooms, key=lambda room: room.data["name"])


def select_rooms(rooms, target):
    if target == "all":
        return rooms
    matches = [room for room in rooms if target in (room.data["name"], room.data["serial"])]
    if len(matches) != 1:
        raise FleetError("device must be an exact registered name, serial, or all")
    return matches


def dns_sd_labels(text):
    """Decode DNS presentation escapes without losing unescaped label boundaries."""
    labels, label = [], bytearray()
    index = 0
    try:
        while index < len(text):
            character = text[index]
            index += 1
            if character == ".":
                if not label:
                    return None
                labels.append(label.decode("utf-8"))
                label.clear()
            elif character == "\\":
                if index == len(text):
                    return None
                character = text[index]
                if character in "0123456789":
                    digits = text[index:index + 3]
                    if len(digits) != 3 or any(digit not in "0123456789" for digit in digits):
                        return None
                    value = int(digits, 10)
                    if value > 255:
                        return None
                    label.append(value)
                    index += 3
                else:
                    label.extend(character.encode("utf-8"))
                    index += 1
            else:
                label.extend(character.encode("utf-8"))
        if label:
            labels.append(label.decode("utf-8"))
    except UnicodeError:
        return None
    return labels


def parse_lookup(output, name):
    """Read only exact-name dns-sd -L answers, deduplicating network interfaces."""
    pattern = re.compile(
        r"^[ \t]*(?:\d{1,2}:\d{2}:\d{2}(?:\.\d+)?[ \t]+)?"
        r"(.+?)[ \t]+can be reached at ([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*"
        r"\.local\.?):(\d+)\b",
        re.MULTILINE,
    )
    endpoints = []
    expected_labels = [name, *SERVICE.split("."), "local"]
    for service_name, host, port_text in pattern.findall(output):
        if dns_sd_labels(service_name) != expected_labels:
            continue
        endpoint = (host.rstrip(".").lower(), int(port_text))
        if 1 <= endpoint[1] <= 65535 and endpoint not in endpoints:
            endpoints.append(endpoint)
    return endpoints


def lookup(name, timeout):
    # dns-sd keeps running even after an answer. run() kills/reaps it on timeout;
    # TimeoutExpired.output contains answers already received, sometimes as bytes.
    try:
        result = subprocess.run(
            ["/usr/bin/dns-sd", "-L", name, SERVICE, "local"],
            capture_output=True, text=True, timeout=timeout, check=False,
        )
        if result.returncode:
            raise FleetError("dns-sd lookup failed")
        output = result.stdout
    except subprocess.TimeoutExpired as error:
        output = error.output or ""
    except OSError:
        raise FleetError("dns-sd unavailable; refresh requires macOS") from None
    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="replace")
    endpoints = parse_lookup(output, name)
    if not endpoints:
        raise FleetError("no exact service answer within lookup timeout")
    if len(endpoints) != 1:
        raise FleetError("conflicting exact service answers")
    return endpoints[0]


def verify_info(room, ip, port, timeout):
    """Authenticate directly, without redirects/proxies or printing HTTP bodies."""
    try:
        ipaddress.IPv4Address(ip)
    except ValueError:
        raise FleetError("invalid IPv4 endpoint") from None
    connection = http.client.HTTPConnection(ip, port, timeout=timeout)
    try:
        connection.request(
            "GET", "/info", headers={"Authorization": "Bearer " + room.data["token"]}
        )
        response = connection.getresponse()
        if response.status != 200:
            raise FleetError(f"/info HTTP {response.status}")
        body = response.read(1024 * 1024 + 1)
        if len(body) > 1024 * 1024:
            raise FleetError("/info response too large")
        info = json.loads(body)
        if not isinstance(info, dict) or info.get("ok") is not True:
            raise FleetError("/info did not report success")
        if info.get("name") != room.data["name"]:
            raise FleetError("/info friendly name mismatch")
    except (OSError, http.client.HTTPException):
        raise Retryable("/info connection failed") from None
    except (ValueError, UnicodeError):
        raise FleetError("/info invalid JSON") from None
    finally:
        connection.close()


def atomic_update(room, ip, port):
    """Replace in the same private directory; leave every other field intact."""
    temporary = None
    try:
        if room.path.is_symlink():
            raise FleetError("registry entry changed; refresh again")
        current = json.loads(room.path.read_text(encoding="utf-8"))
        if current != room.data:
            raise FleetError("registry entry changed; refresh again")
        updated = dict(current, ip=ip, agentPort=port)
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=room.path.parent,
            prefix="." + room.path.name + ".", suffix=".tmp", delete=False,
        ) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            json.dump(updated, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, room.path)
    except (OSError, ValueError):
        raise FleetError("cannot atomically update registry") from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def report_rooms(rooms, timeout, refresh=False):
    failed = False
    for room in rooms:
        try:
            if refresh:
                host, port = lookup(room.data["name"], timeout)
                # Native fleetctl's HTTP implementation expects IPv4 endpoints.
                addresses = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM)
                ips = list(dict.fromkeys(address[4][0] for address in addresses))
                last_error = FleetError("service hostname has no IPv4 address")
                for ip in ips:
                    try:
                        verify_info(room, ip, port, timeout)
                        break
                    except FleetError as error:
                        last_error = error
                else:
                    raise last_error
                atomic_update(room, ip, port)
            else:
                ip, port = room.data["ip"], room.data["agentPort"]
                verify_info(room, ip, port, timeout)
            print(f'{room.data["name"]}: reachable ({ip}:{port})')
        except (FleetError, OSError) as error:
            reason = str(error) if isinstance(error, FleetError) else "hostname resolution failed"
            print(f'{room.data["name"]}: down ({reason})')
            failed = True
    return int(failed)


def json_blocks(output):
    """Decode complete JSON blocks amongst native banners and progress lines."""
    decoder = json.JSONDecoder()
    end = 0
    for match in re.finditer(r"(?m)^[ \t]*[\{\[]", output):
        start = match.end() - 1
        if start < end:
            continue
        try:
            value, end = decoder.raw_decode(output, start)
        except ValueError:
            continue
        if isinstance(value, dict):
            yield value


def redact(output, rooms):
    # Preserve native formatting. Mask both literal and JSON-escaped known tokens
    # and token fields returned by endpoints, including tokens absent in registry.
    for secret in sorted((room.data["token"] for room in rooms), key=len, reverse=True):
        for text in (json.dumps(secret, ensure_ascii=True)[1:-1], secret):
            output = output.replace(text, "[redacted]")
    output = re.sub(
        r'(?i)("(?:token|access_?token|refresh_?token)"\s*:\s*)"(?:\\.|[^"\\])*"',
        r'\1"[redacted]"', output,
    )
    return re.sub(
        r"(?im)^.*\b(?:proxy-)?authorization\s*:.*$", "[redacted header]", output
    )


def native_positionals(args):
    """Follow fleetctl's flag-value rules when recognizing dev update."""
    positionals = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in VALUELESS or (arg.startswith("--") and "=" in arg):
            index += 1
        elif arg.startswith("--"):
            index += 2
        else:
            positionals.append(arg)
            index += 1
    return positionals


def forward(cli, registry, rooms, args):
    args = list(args)
    target = "all"
    has_target = False
    for index, arg in enumerate(args):
        if arg.split("=", 1)[0] in {"--token", "--host", "--port"}:
            raise FleetError("host, port, and token arguments are unsupported; use the registry")
        if arg == "--device" or arg.startswith("--device="):
            if has_target:
                raise FleetError("pass only one device selector")
            has_target = True
            if "=" in arg:
                target = arg.split("=", 1)[1]
            elif index + 1 < len(args):
                target = args[index + 1]
            else:
                raise FleetError("device selector requires a value")
    selected = select_rooms(rooms, target)
    if not has_target:
        args += ["--device", "all"]
    try:
        result = subprocess.run(
            [str(cli), *args], env={**os.environ, "IMMORTAL_FLEET_DIR": str(registry.resolve())},
            capture_output=True, text=True, errors="replace", check=False,
        )
    except OSError:
        raise FleetError("cannot execute fleetctl; set --cli or IMMORTAL_FLEET_CLI") from None
    sys.stdout.write(redact(result.stdout, rooms))
    sys.stderr.write(redact(result.stderr, rooms))
    if native_positionals(args)[:2] == ["dev", "update"]:
        blocks = list(json_blocks(result.stdout)) + list(json_blocks(result.stderr))
        if any(block.get("ok") is False for block in blocks):
            print("dev update failed: agent returned ok:false", file=sys.stderr)
            return result.returncode if result.returncode > 0 else 1
        if sum(block.get("ok") is True for block in blocks) < len(selected):
            print("dev update failed: missing JSON success for a target", file=sys.stderr)
            return result.returncode if result.returncode > 0 else 1
    return result.returncode if result.returncode >= 0 else 1


def remaining(deadline, cap):
    seconds = min(cap, deadline - time.monotonic())
    if seconds <= 0:
        raise FleetError("replacement verification timed out")
    return seconds


def deploy_get(room, route, limit, timeout, deadline=math.inf, raw=False, hashing=False):
    deadline = min(deadline, time.monotonic() + timeout)
    connection = http.client.HTTPConnection(
        room.data["ip"], room.data["agentPort"], timeout=remaining(deadline, timeout)
    )
    response = None
    try:
        connection.request("GET", route, headers={
            "Authorization": "Bearer " + room.data["token"], "Connection": "close",
        })
        sock = connection.sock
        response = connection.getresponse()
        if response.status != 200:
            raise FleetError(f"deploy HTTP {response.status}")
        if hashing:
            digest, size = hashlib.sha256(), 0
            while size < limit:
                sock.settimeout(remaining(deadline, timeout))
                chunk = response.read1(min(65536, limit - size))
                remaining(deadline, timeout)
                if not chunk:
                    return size, digest.hexdigest()
                size += len(chunk)
                digest.update(chunk)
                if size < limit and getattr(response, "length", None) == 0:
                    return size, digest.hexdigest()
            raise FleetError("installed APK exceeds expected size")
        # procfs advertises Content-Length:0 but streams bytes until close.
        sock.settimeout(remaining(deadline, timeout))
        body = response.fp.read(limit) if raw else response.read(limit + 1)
        remaining(deadline, timeout)
        if len(body) > limit or (raw and len(body) == limit):
            raise FleetError("deploy response exceeds size limit")
        return body
    except (OSError, http.client.HTTPException):
        raise Retryable("deploy connection interrupted") from None
    finally:
        if response is not None:
            response.close()
        connection.close()


def own_apk(room, package, deadline=math.inf):
    maps = deploy_get(room, "/fs/read?" + urlencode({"path": "/proc/self/maps"}),
                      4 * 1024 * 1024, 20, deadline, raw=True).decode("utf-8")
    paths = set(re.findall(
        r"(?m)(?:^|[ \t])(/data/app/" + re.escape(package) + r"-[^/\s]+/base\.apk)[ \t]*$",
        maps,
    ))
    if not paths:
        return None
    if len(paths) != 1:
        raise FleetError("ambiguous own installed APK")
    path = paths.pop()
    parent = path.rsplit("/", 1)[0]
    listing = json.loads(deploy_get(
        room, "/fs/list?" + urlencode({"path": parent}), 1024 * 1024, 20, deadline
    ))
    if not isinstance(listing, dict) or listing.get("ok") is not True or listing.get("path") != parent:
        raise FleetError("invalid own APK listing")
    entries = [entry for entry in listing["entries"]
               if entry["name"] == "base.apk" and entry["type"] == "file"]
    if len(entries) != 1 or type(entries[0].get("mtime")) is not int or entries[0]["mtime"] < 0:
        raise FleetError("missing own APK mtime")
    return path, entries[0]["mtime"]


def deploy(cli, registry, rooms, apk, package):
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", package, re.ASCII):
        raise FleetError("invalid package name")
    digest, size = hashlib.sha256(), 0
    try:
        with Path(apk).open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                size += len(chunk)
                digest.update(chunk)
    except OSError:
        raise FleetError("cannot read local APK") from None
    if not size:
        raise FleetError("local APK is empty")
    failed = False
    for room in rooms:
        try:
            verify_info(room, room.data["ip"], room.data["agentPort"], 5)
            before = own_apk(room, package)
            result = subprocess.run(
                [str(cli), "dev", "update", str(apk), "--package", package,
                 "--device", room.data["name"]],
                env={**os.environ, "IMMORTAL_FLEET_DIR": str(registry.resolve())},
                capture_output=True, text=True, errors="replace", check=False,
            )
            blocks = list(json_blocks(result.stdout)) + list(json_blocks(result.stderr))
            if any(block.get("ok") is False for block in blocks):
                raise FleetError("agent returned ok:false")
            installed = any(block.get("ok") is True and block.get("result") == "installed"
                            and block.get("packageName") == package for block in blocks)
            marker = f"installing {package} ..."
            tail = result.stdout.rpartition(marker)[2].strip()
            lost = (result.returncode == 1 and marker in result.stdout
                    and re.search(r"\(transport error\)|RemoteDisconnected", result.stderr)
                    and (not tail or tail.startswith("read failed:")))
            if result.returncode not in (0, 1) or not (installed or (before and lost)):
                raise FleetError("native install failed or returned no valid installed result")
            if before:
                deadline = time.monotonic() + 90
                while True:
                    try:
                        verify_info(room, room.data["ip"], room.data["agentPort"], remaining(deadline, 5))
                        current = own_apk(room, package, deadline)
                        if current is None or current == before:
                            raise Retryable("installed APK path and mtime are unchanged")
                        actual = deploy_get(room, "/fs/read?" + urlencode({"path": current[0]}),
                                            size + 1, 30, deadline, hashing=True)
                        if actual != (size, digest.hexdigest()):
                            raise FleetError("installed APK SHA-256 mismatch")
                        break
                    except Retryable:
                        time.sleep(remaining(deadline, 2))
                proof = "verified installed APK SHA-256"
            else:
                if result.returncode != 0:
                    raise FleetError("native install failed")
                proof = "native result: installed"
            print(f'{room.data["name"]}: deployed {package} ({proof})')
        except (FleetError, OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            reason = str(error) if isinstance(error, FleetError) else "invalid deploy response or I/O failure"
            print(f'{room.data["name"]}: deploy failed ({reason})')
            failed = True
    return int(failed)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes rejected arguments, which might contain tokens.
        raise FleetError("invalid arguments; see --help")


def main(argv=None):
    parser = Parser(
        description="macOS room fleet helper: exact registered-name discovery and fleetctl forwarding.",
        epilog=(
            "Put helper options before COMMAND; native options after it.\n"
            "refresh [--device NAME|SERIAL|all]  rediscover, authenticate, update IP/port\n"
            "status  [--device NAME|SERIAL|all]  check cached endpoints individually\n"
            "deploy APK [--package PKG] [--device NAME|all]  verify installation per room\n"
            "deploy defaults to com.immortal.launcher.debug; self recovery waits at most 90s.\n"
            "Other commands pass to fleetctl, defaulting to --device all.\n"
            "No automatic refresh. dev update requires JSON success for every target.\n\n"
            "Examples:\n"
            '  room-fleet.py refresh\n'
            '  room-fleet.py status --device "Family Area"\n'
            '  room-fleet.py info\n'
            '  room-fleet.py deploy /path/to/signed.apk --device all\n'
            '  room-fleet.py dev update /path/to/signed.apk --no-pause\n'
            '  room-fleet.py screensaver get --device "Living Area"'
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--cli", default=os.environ.get("IMMORTAL_FLEET_CLI")
                        or str(ROOT / "build/fleet/fleetctl"),
                        help="fleetctl path (default: IMMORTAL_FLEET_CLI or build/fleet/fleetctl)")
    parser.add_argument("--registry", default=os.environ.get("IMMORTAL_FLEET_DIR")
                        or str(ROOT / "provisioning/fleet"),
                        help="registry directory (default: IMMORTAL_FLEET_DIR or provisioning/fleet)")
    parser.add_argument("--timeout", type=float, default=5.0,
                        help="seconds per dns-sd lookup or HTTP operation (default: 5)")
    parser.add_argument("command", nargs=argparse.REMAINDER, metavar="COMMAND",
                        help="refresh, status, deploy, or a native fleetctl command with arguments")
    try:
        options = parser.parse_args(argv)
        if not options.command:
            parser.print_help()
            return 0
        if not math.isfinite(options.timeout) or options.timeout <= 0:
            raise FleetError("timeout must be positive and finite")
        command = options.command
        if command[0] == "--":
            command = command[1:]
        if not command:
            raise FleetError("command required")
        selection = None
        if command[0] in {"refresh", "status", "deploy"}:
            room_parser = Parser(prog=f"{parser.prog} {command[0]}")
            room_parser.add_argument("--device", default="all")
            if command[0] == "deploy":
                room_parser.add_argument("apk")
                room_parser.add_argument("--package", default="com.immortal.launcher.debug")
            selection = room_parser.parse_args(command[1:])
        registry = Path(options.registry).expanduser().resolve()
        rooms = load_rooms(registry)
        if selection is not None:
            if command[0] == "deploy":
                return deploy(Path(options.cli).expanduser(), registry,
                              select_rooms(rooms, selection.device),
                              Path(selection.apk).expanduser(), selection.package)
            return report_rooms(select_rooms(rooms, selection.device), options.timeout,
                                refresh=command[0] == "refresh")
        return forward(Path(options.cli).expanduser(), registry, rooms, command)
    except FleetError as error:
        print(f"room-fleet: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
