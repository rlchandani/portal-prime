"""fleetctl wrapper. Every call passes the lock guard first; tokens never leave this module unmasked."""

import hashlib
import http.client
import json
import subprocess
from dataclasses import dataclass
from typing import Any, Optional

from .lock import guard

DEFAULT_TIMEOUT_S = 25
PULL_TIMEOUT_S = 120


@dataclass(frozen=True)
class Reply:
    rc: int
    body: Optional[Any]
    text: str

    @property
    def ok(self):
        return self.rc == 0 and isinstance(self.body, dict) and self.body.get("ok") is True


class Fleet:
    def __init__(self, fleetctl, locks, redactor):
        self.fleetctl = fleetctl
        self.locks = locks
        self.redactor = redactor

    def _run(self, device, args, timeout, binary=False):
        guard(self.locks, device.serial)
        cmd = [self.fleetctl, *args, "--device", device.serial]
        try:
            return subprocess.run(cmd, capture_output=True, timeout=timeout, text=not binary)
        except subprocess.TimeoutExpired:
            return None

    def raw(self, device, method, path, body=None, timeout=DEFAULT_TIMEOUT_S):
        args = ["raw", method, path] + ([json.dumps(body)] if body is not None else [])
        p = self._run(device, args, timeout)
        if p is None:
            return Reply(124, None, "timeout")
        text = p.stdout.strip()
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = None
        if isinstance(parsed, dict) and "token" in parsed:
            self.redactor.add(parsed.get("token"))
        return Reply(p.returncode, parsed, self.redactor.text(text or p.stderr.strip())[:2000])

    def action(self, device, body, timeout=DEFAULT_TIMEOUT_S):
        return self.raw(device, "POST", "/action", body, timeout)

    def info(self, device):
        return self.raw(device, "GET", "/info")

    def health_line(self, device):
        p = self._run(device, ["health"], DEFAULT_TIMEOUT_S)
        if p is None:
            return 124, "timeout"
        return p.returncode, self.redactor.text((p.stdout or p.stderr).strip())

    def logcat(self, device, lines):
        p = self._run(device, ["logcat", "--lines", str(lines)], 60)
        return [] if p is None or p.returncode != 0 else p.stdout.splitlines()

    def sha256_of(self, device, remote_path):
        guard(self.locks, device.serial)
        cmd = [self.fleetctl, "pull", remote_path, "-", "--device", device.serial]
        h = hashlib.sha256()
        size = 0
        try:
            with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as p:
                for chunk in iter(lambda: p.stdout.read(1 << 20), b""):
                    h.update(chunk)
                    size += len(chunk)
                rc = p.wait(timeout=PULL_TIMEOUT_S)
        except (OSError, subprocess.TimeoutExpired):
            return None, 0
        return (h.hexdigest(), size) if rc == 0 else (None, size)


class SessionClient:
    """A paired-phone test session against one Portal: the token stays in memory and is masked."""

    def __init__(self, device, token, locks, redactor):
        self.device = device
        self._token = token
        self.locks = locks
        self.redactor = redactor
        redactor.add(token)

    @property
    def session_id(self):
        # RemoteSessionPolicy.idOf: the first 8 bytes of SHA-256(token), as hex.
        return hashlib.sha256(self._token.encode("utf-8")).hexdigest()[:16]

    def call(self, method, path, body=None, timeout=15):
        guard(self.locks, self.device.serial)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Authorization": "Bearer " + self._token}
        if data is not None:
            headers["Content-Type"] = "application/json"
        conn = http.client.HTTPConnection(self.device.host, self.device.port, timeout=timeout)
        try:
            conn.request(method, path, body=data, headers=headers)
            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", "replace")
        except OSError as e:
            return Reply(1, None, f"connection error {type(e).__name__}")
        finally:
            conn.close()
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = None
        return Reply(0 if 200 <= resp.status < 300 else resp.status, parsed, self.redactor.text(raw)[:2000])
