#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Run local Portal gates and verify an APK; never authenticate, publish or deploy."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

GIT = ["git", "-c", "core.hooksPath=/dev/null", "--no-optional-locks"]
PACKAGES = {"release": "com.immortal.launcher",
            "debug": "com.immortal.launcher.debug",
            "performance": "com.immortal.launcher.debug"}


class CheckFailed(Exception):
    pass


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class Checks:
    def __init__(self, root, output, seconds, receipt, environment):
        self.root, self.output, self.receipt = root, output, receipt
        self.environment = environment
        self.deadline = time.monotonic() + seconds

    def run(self, name, argv, capture=False):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise CheckFailed("The local check deadline expired.")
        log = self.output / f"{len(self.receipt['checks']) + 1:02d}-{name}.log"
        record = {"name": name, "argv": [str(x) for x in argv], "log": log.name}
        self.receipt["checks"].append(record)
        start = time.monotonic()
        print(f"Checking {name}…", flush=True)
        try:
            with log.open("wb") as stream:
                child = subprocess.Popen(argv, cwd=self.root, stdout=stream,
                                         stderr=subprocess.STDOUT, start_new_session=True,
                                         env=self.environment)
                try:
                    code = child.wait(timeout=remaining)
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    self.stop_group(child)
                    raise CheckFailed(f"{name} timed out or was interrupted; its process group was stopped.")
            record["exitCode"] = code
            record["durationMs"] = round((time.monotonic() - start) * 1000)
            if code != 0:
                raise CheckFailed(f"{name} failed with exit {code}; see {log.name}.")
            if capture:
                if log.stat().st_size > 8 * 1024 * 1024:
                    raise CheckFailed(f"{name} produced unexpectedly large metadata.")
                return log.read_text(errors="replace")
            return ""
        except OSError as error:
            raise CheckFailed(f"Could not run {name}: {error.strerror}.") from error

    @staticmethod
    def stop_group(child):
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            child.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
        # Also stop descendants when the parent has already handled SIGTERM.
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        child.wait()


def source_inputs(checks):
    raw = checks.run("source-files", GIT + ["ls-files", "--cached", "--others",
                                          "--exclude-standard", "-z"], capture=True)
    names = sorted(set(raw.split("\0")) - {""})
    h, count = hashlib.sha256(), 0
    for name in names:
        path = Path(name)
        selected = (name.startswith(("app/", "scripts/", "gradle/", ".github/workflows/"))
                    or name in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                                "gradlew", "gradlew.bat", "version.json", "catalog.json"})
        if not selected or name.startswith("app/build/") or "__pycache__" in path.parts:
            continue
        file = checks.root / path
        value = digest(file) if file.is_file() else "missing"
        h.update(name.encode() + b"\0" + value.encode() + b"\0")
        count += 1
    return {"sha256": h.hexdigest(), "fileCount": count}


def version(root):
    try:
        manifest = json.loads((root / "version.json").read_text())
        gradle = (root / "app/build.gradle.kts").read_text()
        code = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, re.M)
        name = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.M)
        if len(code) != 1 or len(name) != 1:
            raise CheckFailed("Gradle must declare exactly one versionCode and versionName.")
        if (type(manifest.get("versionCode")) is not int or manifest["versionCode"] < 1
                or manifest["versionCode"] != int(code[0])
                or manifest.get("versionName") != name[0]):
            raise CheckFailed("Gradle and version.json versions disagree.")
        return {"versionCode": int(code[0]), "versionName": name[0]}
    except (OSError, ValueError) as error:
        raise CheckFailed("Could not read valid local version metadata.") from error


def build_tools(sdk):
    try:
        choices = [p for p in (sdk / "build-tools").iterdir()
                   if re.fullmatch(r"\d+(?:\.\d+)*", p.name) and p.is_dir()]
        choices.sort(key=lambda p: tuple(map(int, p.name.split("."))), reverse=True)
    except OSError:
        choices = []
    for path in choices:
        if all(os.access(path / tool, os.X_OK) for tool in ("aapt", "apksigner")):
            return path
    raise CheckFailed("Android SDK aapt/apksigner are missing; supply --sdk.")


def apk_identity(checks, tools, path, label):
    badging = checks.run(label + "-metadata", [str(tools / "aapt"), "dump", "badging",
                                             str(path)], capture=True)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
                      badging, re.M)
    if not match:
        raise CheckFailed(f"{label} APK metadata could not be verified.")
    certs = checks.run(label + "-signature", [str(tools / "apksigner"), "verify",
                                            "--print-certs", str(path)], capture=True)
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$",
                        certs, re.M)
    if not signers:
        raise CheckFailed(f"{label} APK has no verified signing certificate.")
    return {"package": match[1], "versionCode": int(match[2]), "versionName": match[3],
            "signerSha256": sorted(s.lower() for s in signers),
            "bytes": path.stat().st_size, "sha256": digest(path)}


def jvm_result(root, not_before_ns):
    files = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    try:
        for file in files:
            if file.stat().st_mtime_ns < not_before_ns - 1_000_000_000:
                raise CheckFailed("Stale JVM reports cannot establish this run's test results.")
            report = ET.parse(file).getroot()
            if report.tag != "testsuite":
                raise CheckFailed("Unexpected JVM test report structure.")
            for key in totals:
                value = int(report.attrib[key])
                if value < 0:
                    raise CheckFailed("Invalid negative JVM test count.")
                totals[key] += value
    except (OSError, ET.ParseError, ValueError, KeyError) as error:
        raise CheckFailed("JVM report evidence is incomplete or invalid.") from error
    if not files or totals["tests"] == 0 or any(totals[k] for k in ("failures", "errors", "skipped")):
        raise CheckFailed("JVM reports must contain tests with no failures, errors or skips.")
    return dict(totals, suites=len(files))


def test_count(output, name, pattern):
    match = re.search(pattern, output, re.M)
    if not match or int(match[1]) < 1:
        raise CheckFailed(f"{name} returned no nonempty test-count evidence.")
    return int(match[1])


def execute(args, receipt):
    root = args.repo_root.resolve()
    environment = dict(os.environ, ANDROID_HOME=str(args.sdk), ANDROID_SDK_ROOT=str(args.sdk))
    checks = Checks(root, args.output, args.timeout_seconds, receipt, environment)
    if sys.version_info < (3, 12):
        raise CheckFailed("Python 3.12 or newer is required.")
    if os.name != "posix":
        raise CheckFailed("This checker currently requires macOS/Linux for process-group cleanup.")
    if not args.baseline_apk.is_file():
        raise CheckFailed("The mandatory local baseline APK is missing.")
    receipt["version"] = version(root)
    checks.run("version-sync", ["bash", "scripts/check-version-sync.sh"])
    top = checks.run("git-root", GIT + ["rev-parse", "--show-toplevel"], capture=True).strip()
    if Path(top).resolve() != root:
        raise CheckFailed("--repo-root must be the repository root.")
    head = checks.run("git-head", GIT + ["rev-parse", "HEAD"], capture=True).strip()
    dirty = bool(checks.run("git-status", GIT + ["status", "--porcelain"], capture=True).strip())
    start_source = source_inputs(checks)
    receipt["source"] = dict(start_source, head=head, dirty=dirty)
    tools = build_tools(args.sdk)
    receipt["tools"] = {"python": sys.version.split()[0], "androidBuildTools": tools.name}
    node_version = checks.run("node-version", ["node", "--version"], capture=True).strip()
    if not re.fullmatch(r"v(?:2[2-9]|[3-9]\d|\d{3,})\.\d+\.\d+", node_version):
        raise CheckFailed("Node.js 22 or newer is required.")
    receipt["tools"]["node"] = node_version
    baseline = apk_identity(checks, tools, args.baseline_apk, "baseline")
    if baseline["package"] != PACKAGES[args.variant]:
        raise CheckFailed("Baseline package differs from the selected variant.")
    receipt["baseline"] = baseline

    slideshow = ["scripts/test_slideshow_sources.mjs", "scripts/test_slideshow_labels.mjs",
                 "scripts/tests/slideshow_native_filmstrip.test.cjs"]
    remote = sorted(str(p.relative_to(root)) for p in (root / "app/src/test/js").glob("*.test.mjs"))
    if not remote or not all((root / p).is_file() for p in slideshow):
        raise CheckFailed("Required JavaScript test files are missing.")
    receipt["javascript"] = {}
    for label, paths in (("slideshow-js", slideshow), ("remote-js", remote)):
        output = checks.run(label, ["node", "--test", "--test-reporter=tap", *paths], capture=True)
        receipt["javascript"][label] = test_count(output, label, r"^# tests (\d+)\s*$")
    output = checks.run("python-tests", [sys.executable, "-m", "unittest", "discover",
                                       "-s", "scripts", "-p", "test_portal_*.py"], capture=True)
    receipt["pythonTests"] = test_count(output, "Python tests", r"^Ran (\d+) tests? in ")
    prior = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if prior:
        archived = args.output / "prior-jvm-reports"
        archived.mkdir(mode=0o700)
        for file in prior:
            shutil.move(str(file), str(archived / file.name))
    receipt["archivedPriorJvmReports"] = len(prior)
    android_start_ns = time.time_ns()
    checks.run("android-gates", ["./gradlew", ":app:testDebugUnitTest", "--rerun",
                                ":app:assemble" + args.variant.title(), "--offline", "--no-daemon",
                                "--console=plain", "--max-workers=1",
                                "-Pkotlin.compiler.execution.strategy=in-process",
                                "-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8"])
    receipt["jvm"] = jvm_result(root, android_start_ns)
    apk = root / f"app/build/outputs/apk/{args.variant}/app-{args.variant}.apk"
    if not apk.is_file():
        raise CheckFailed("The selected build produced no APK.")
    pending = args.output / ".candidate.pending.apk"
    shutil.copyfile(apk, pending)
    candidate = apk_identity(checks, tools, pending, "candidate")
    if candidate["package"] != PACKAGES[args.variant]:
        raise CheckFailed("Candidate APK package differs from the selected variant.")
    for key, value in receipt["version"].items():
        if candidate[key] != value:
            raise CheckFailed(f"Candidate APK {key} is stale or inconsistent.")
    if candidate["signerSha256"] != baseline["signerSha256"]:
        raise CheckFailed("Candidate signing certificates differ from the local baseline.")
    if digest(args.baseline_apk) != baseline["sha256"]:
        raise CheckFailed("The baseline APK changed during checks.")
    if digest(apk) != candidate["sha256"]:
        raise CheckFailed("The built APK changed during inspection.")
    if source_inputs(checks) != start_source:
        raise CheckFailed("Source/build inputs changed during checks; rerun against a stable tree.")
    if time.monotonic() >= checks.deadline:
        raise CheckFailed("The local check deadline expired before readiness was established.")
    pending.rename(args.output / "candidate.apk")
    receipt["artifact"] = dict(candidate, file="candidate.apk", variant=args.variant)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--baseline-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True,
                        help="New directory for private logs, candidate and receipt; never overwritten.")
    parser.add_argument("--variant", choices=PACKAGES, default="release")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME")
                        or os.environ.get("ANDROID_SDK_ROOT") or str(Path.home() / "Library/Android/sdk")))
    parser.add_argument("--timeout-seconds", type=float, default=900)
    args = parser.parse_args()
    if not 0 < args.timeout_seconds <= 1800:
        parser.error("--timeout-seconds must be greater than zero and at most 1800.")
    args.output, args.baseline_apk, args.sdk = (p.resolve() for p in
                                              (args.output, args.baseline_apk, args.sdk))
    root = args.repo_root.resolve()
    if args.output.is_relative_to(root) and not args.output.is_relative_to(root / "app/build"):
        print("Place output outside the repository or under app/build; source directories are not modified.",
              file=sys.stderr)
        return 2
    try:
        args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    except OSError:
        print("Choose a new writable output directory; existing evidence was not overwritten.", file=sys.stderr)
        return 2
    started = time.monotonic()
    receipt = {"schemaVersion": 1, "scope": "local-artifact-readiness", "status": "failed",
               "published": False, "deployed": False, "checks": [],
               "startedAt": datetime.now(timezone.utc).isoformat()}
    try:
        execute(args, receipt)
        receipt["status"] = "ready"
    except (CheckFailed, OSError, ValueError, KeyboardInterrupt) as error:
        receipt["error"] = str(error) or "The local check was interrupted."
    finally:
        (args.output / ".candidate.pending.apk").unlink(missing_ok=True)
        receipt["durationMs"] = round((time.monotonic() - started) * 1000)
        receipt["finishedAt"] = datetime.now(timezone.utc).isoformat()
        temporary = args.output / ".receipt.pending.json"
        temporary.write_text(json.dumps(receipt, indent=2) + "\n")
        temporary.replace(args.output / "receipt.json")
    print(json.dumps({"status": receipt["status"], "receipt": str(args.output / "receipt.json"),
                      "error": receipt.get("error")}))
    return 0 if receipt["status"] == "ready" else 1


if __name__ == "__main__":
    raise SystemExit(main())
