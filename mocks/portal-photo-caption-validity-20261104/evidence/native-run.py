from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import time
import zipfile

P = Path("/private/tmp/portal-ux-next-20261104-141902")
N = P / "native-owned"
PHASE = sys.argv[1]
assert PHASE in ("before", "after")
PKG = "com.immortal.captiondatefixture"
APK = N / "caption-date-fixture.apk"
SOURCE = P / ("before-debug-1.162.apk" if PHASE == "before" else "candidate-debug-1.163.apk")
REMOTE = "/sdcard/Android/data/" + PKG


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def adb(*args, check=True, timeout=40):
    return subprocess.run(
        ["adb", "-s", "192.168.0.70:5555", *args],
        capture_output=True, check=check, timeout=timeout)


assert adb("shell", "getprop", "ro.serialno").stdout.decode().strip() == "FAMILY_PORTAL_SERIAL"
assert not adb("shell", "pm", "path", PKG, check=False).stdout.strip()
with zipfile.ZipFile(APK) as archive:
    assert archive.read("assets/source.apk") == SOURCE.read_bytes()
    assert archive.read("classes.dex") == (N / "frozen-dex/classes.dex").read_bytes()
with (P / f"native-{PHASE}.claim").open("x") as claim:
    claim.write("One invocation of frozen generated metadata fixture\n")
    claim.flush()
    os.fsync(claim.fileno())

started = time.monotonic()
try:
    install = adb("install", "-r", str(APK))
    (P / f"native-{PHASE}-install.log").write_bytes(install.stdout + install.stderr)
    assert b"Success" in install.stdout
    launch = adb("shell", "am", "start", "-n", PKG + "/.PhotoCaptionDateActivity")
    assert b"Error" not in launch.stdout + launch.stderr
    deadline = time.monotonic() + 35
    while True:
        observed = adb("shell", "cat", REMOTE + "/files/report.json", check=False, timeout=10)
        if observed.returncode == 0:
            report = json.loads(observed.stdout)
            break
        assert time.monotonic() < deadline, "Owned metadata report deadline"
        time.sleep(.5)
    (P / f"native-{PHASE}-report.json").write_text(json.dumps(report, indent=2) + "\n")
    assert report["error"] is None and report["setupFailures"] == 0
    assert report["tests"] == 16 and report["sdk"] == 29
    assert report["fixturePackage"] == PKG and not report["internetPermission"]
    assert report["sourceSha256"] == digest(SOURCE) and report["sourceBytes"] == SOURCE.stat().st_size
    assert report["actualEmbeddedOrigin"] and report["generatedJpegBytes"] > 0
    assert all(report[key] for key in (
        "generatedBitmapRecycled", "allProcessDefaultsRestored", "ownerHandlerRetired", "cleanup"))
    assert report["streamsConstructed"] == report["streamsClosed"] == 16
    assert not any(report[key] for key in (
        "targetContextPassed", "targetSaveAttributesInvoked", "targetPlaceLookupInvoked"))
    assert len({case["name"] for case in report["cases"]}) == 16
    assert all(case["actualReadInvoked"] and case["streamClosed"] and case["setupError"] is None
               for case in report["cases"])
    assert all(case["original"] == case["originalAfter"] and case["fallback"] == case["fallbackAfter"]
               for case in report["cases"])
    assert all(case["gpsTolerance"] == .00001 and case["memoryNowLiteral"] == 1898683200000
               for case in report["cases"])
    invalid = [case for case in report["cases"] if case["name"].startswith("invalid")]
    controls = [case for case in report["cases"] if not case["name"].startswith("invalid")]
    assert len(invalid) == 9 and len(controls) == 7
    assert all(case["passed"] and not case["errors"] for case in controls)
    if PHASE == "before":
        assert not report["ok"] and report["failures"] == 9
        assert all(not case["passed"] and case["dateMillis"] is not None and
                   any("Literal nullable epoch" in error for error in case["errors"]) for case in invalid)
    else:
        assert report["ok"] and report["failures"] == 0
        assert all(case["passed"] and not case["errors"] and case["dateMillis"] is None and
                   case["formatted"] is None and case["memoryYears"] is None for case in invalid)
    print(json.dumps({"phase": PHASE, "tests": 16, "failures": report["failures"],
                      "controls": 7, "setupFailures": 0, "actualExif": True,
                      "fixtureDexSha256": digest(N / "frozen-dex/classes.dex"),
                      "sourceSha256": digest(SOURCE)}))
finally:
    result = adb("uninstall", PKG, check=False)
    (P / f"native-{PHASE}-uninstall.log").write_bytes(result.stdout + result.stderr)
    assert b"Success" in result.stdout
    retired = {
        "packageAbsent": not adb("shell", "pm", "path", PKG, check=False).stdout.strip(),
        "processAbsent": not adb("shell", "pidof", PKG, check=False).stdout.strip(),
        "externalDirectoryAbsent": adb("shell", "test", "!", "-e", REMOTE, check=False).returncode == 0,
    }
    (P / f"native-{PHASE}-retirement.json").write_text(json.dumps(retired, indent=2) + "\n")
    assert all(retired.values())
