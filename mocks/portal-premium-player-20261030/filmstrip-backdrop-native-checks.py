#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Real RenderScript pixel/ownership checks, only on the dedicated API 34 test AVD."""
import argparse
import hashlib
import json
import pathlib
import struct
import subprocess
import time


SERIAL = "emulator-5556"
AVD = "PortalMusic20261030"
PACKAGE = "com.immortal.launcher.debug"
COMPONENT = PACKAGE + "/com.immortal.launcher.FilmstripBackdropPreviewActivity"
REMOTE = "cache/filmstrip-backdrop-native"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", choices=[SERIAL], default=SERIAL)
    parser.add_argument("--timeout", type=int, default=150)
    args = parser.parse_args()
    out = pathlib.Path(__file__).resolve().parent / "evidence" / "filmstrip-backdrop-native"
    out.mkdir(parents=True, exist_ok=True)

    def adb(*command, check=True):
        return subprocess.run(
            ["adb", "-s", args.serial, *command],
            capture_output=True, check=check, timeout=20,
        )

    assert adb("emu", "avd", "name").stdout.decode().splitlines()[0] == AVD
    assert adb("shell", "getprop", "ro.build.version.sdk").stdout.decode().strip() == "34"
    adb("shell", "am", "force-stop", PACKAGE)
    # Delete only this fixture's old JSON so an interrupted launch cannot look successful.
    adb("shell", "run-as", PACKAGE, "rm", "-f", REMOTE + "/checks.json")
    adb("shell", "am", "start", "-W", "-n", COMPONENT)
    started = time.monotonic()
    report = None
    while time.monotonic() - started < args.timeout:
        result = adb("exec-out", "run-as", PACKAGE, "cat", REMOTE + "/checks.json", check=False)
        if result.returncode == 0:
            try:
                report = json.loads(result.stdout)
            except json.JSONDecodeError:
                pass  # The worker may be replacing the progress report.
            if report and report.get("complete"):
                break
        time.sleep(0.35)
    if not report or not report.get("complete"):
        raise TimeoutError("Native worker did not publish a complete result")
    report.update({
        "serial": SERIAL, "avd": AVD, "physicalPortalPerformanceMeasured": False,
        "hostPixelComparisons": [], "artifacts": [],
    })
    (out / "ready.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)
    names = adb("shell", "run-as", PACKAGE, "ls", REMOTE).stdout.decode().splitlines()
    for name in names:
        if name.endswith(".png") and "/" not in name:
            content = adb("exec-out", "run-as", PACKAGE, "cat", REMOTE + "/" + name).stdout
            (out / name).write_bytes(content)
            report["artifacts"].append({
                "file": name, "sha256": hashlib.sha256(content).hexdigest(),
            })
    if report.get("success"):
        assert report["originalInMemorySources"] == 12
        assert len(report["scenarios"]) == 5
        assert [row["passes"] for row in report["scenarios"]] == [1, 2, 3, 6, 8]
        assert all(row["pixelIdenticalSources"] == 12 for row in report["scenarios"])
        for index in range(12):
            oracle = (out / f"oracle-{index:02}.png").read_bytes()
            session = (out / f"session-{index:02}.png").read_bytes()
            assert oracle[:8] == session[:8] == b"\x89PNG\r\n\x1a\n"
            assert struct.unpack(">II", oracle[16:24]) == struct.unpack(">II", session[16:24])
            assert oracle == session, index  # Identical PNG bytes also prove identical decoded pixels.
            report["hostPixelComparisons"].append(f"oracle-{index:02} == session-{index:02} (PNG bytes)")
    (out / "checks.json").write_text(json.dumps(report, indent=2) + "\n")
    if not report.get("success"):
        raise AssertionError(report.get("failure", "Native worker failed"))
    print(f"PASS: {report['passed']} native checks; 60 pixel-identical source/scenario pairs; "
          f"12 independent host PNG comparisons on {AVD}, API 34.")
    print("Evidence:", out)
    print("Limits: no physical Portal timing or RenderScript context-creation savings claim; "
          "deterministic pre-interruption only, not interruption inside a native blur pass.")


if __name__ == "__main__":
    main()
