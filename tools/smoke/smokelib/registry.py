"""Portals come from fleetctl's registry (`fleet/*.json` beside the binary) at runtime, never from code."""

import glob
import json
import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Device:
    name: str
    serial: str
    model: str
    # Only the test-session client reads these; they never reach output (repr=False, redacted).
    host: str = field(default="", repr=False)
    port: int = field(default=0, repr=False)
    token: str = field(default="", repr=False)

    @property
    def short(self):
        """A display id that is not the serial."""
        return "…" + self.serial[-4:]


class RegistryError(Exception):
    pass


def load(fleetctl_path):
    folder = os.path.join(os.path.dirname(os.path.abspath(fleetctl_path)), "fleet")
    files = sorted(glob.glob(os.path.join(folder, "*.json")))
    if not files:
        raise RegistryError("no fleet registry next to fleetctl (expected fleet/*.json)")
    devices = []
    for path in files:
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            devices.append(Device(str(d["name"]), str(d["serial"]), str(d.get("model", "")),
                                  str(d.get("ip", "")), int(d.get("agentPort", 0)), str(d.get("token", ""))))
        except (OSError, ValueError, KeyError) as e:
            raise RegistryError(f"unreadable registry entry {os.path.basename(path)}: {type(e).__name__}") from None
    return tuple(devices)


def select(devices, wanted):
    """[wanted]: names or serials (case-insensitive); empty means every Portal."""
    if not wanted:
        return devices
    keys = {w.strip().lower() for w in wanted}
    picked = tuple(d for d in devices if d.name.lower() in keys or d.serial.lower() in keys)
    missing = keys - {d.name.lower() for d in picked} - {d.serial.lower() for d in picked}
    if missing:
        raise RegistryError(f"not in the registry: {', '.join(sorted(missing))}")
    return picked
