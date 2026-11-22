"""Process runner seam: every external command goes through a Runner so tests can fake the device."""

import subprocess
import time
from dataclasses import dataclass

TIMEOUT_RC = 124


@dataclass(frozen=True)
class Proc:
    rc: int
    out: str
    err: str = ""

    @property
    def ok(self):
        return self.rc == 0


class Runner:
    """Runs commands with a hard timeout (macOS has no `timeout`; subprocess enforces it here)."""

    def run(self, argv, timeout=60, cwd=None):
        try:
            p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd,
                               errors="replace", stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired as e:
            out = e.stdout.decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
            return Proc(TIMEOUT_RC, out, "timeout")
        except OSError as e:
            return Proc(127, "", f"{type(e).__name__}: {e}")
        return Proc(p.returncode, p.stdout, p.stderr)

    def run_bytes(self, argv, timeout=60):
        """For binary output (screenshots, traces)."""
        try:
            p = subprocess.run(argv, capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            return TIMEOUT_RC, b""
        except OSError:
            return 127, b""
        return p.returncode, p.stdout

    def sleep(self, seconds):
        if seconds > 0:
            time.sleep(seconds)

    def now(self):
        return time.time()
