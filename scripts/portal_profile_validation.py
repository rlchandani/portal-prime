#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# This source code is licensed under the MIT license found in the LICENSE file.
"""Validate captured ART profiles without accessing a device or changing an APK.

The external mapper/comparator require outputs below their private tool directory.
Run there, then archive self-contained audits at the caller's evidence destination.
"""
import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import runpy
import shutil
import signal
import subprocess
import sys
import tempfile
import time


class ValidationError(ValueError):
    """A verification gate failed; no successful report may be emitted."""


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def reserve_workspace(tool_root: Path, evidence_root: Path) -> Path:
    tool = Path(tool_root).resolve()
    private = Path("/private/tmp").resolve()
    require(tool.is_dir() and tool != private and tool.is_relative_to(private),
            "Tool directory must be a child of /private/tmp")
    evidence = Path(evidence_root)
    require(evidence.is_absolute(), "Evidence directory must be absolute")
    require(not evidence.exists() and not evidence.is_symlink(),
            "Evidence directory already exists; it will never be overwritten")
    require(not tool.is_relative_to(evidence.resolve()),
            "Evidence directory must not contain the tool directory")
    return Path(tempfile.mkdtemp(prefix="portal-profile-", dir=tool))


def checked_input(path, expected_sha256, cap, profile=False):
    path = Path(path)
    require(path.is_absolute() and path.is_file(), "Input must be an absolute regular file")
    require(isinstance(expected_sha256, str) and re.fullmatch("[a-f0-9]{64}", expected_sha256),
            "Expected SHA-256 must be 64 lowercase hexadecimal characters")
    require(0 < path.stat().st_size <= cap, "Input exceeds its size boundary")
    if profile:
        with path.open("rb") as stream:
            require(stream.read(8) == b"pro\000010\000" and path.stat().st_size >= 17,
                    "Reference profile must be ART v010; no bundled-profile fallback")
    require(digest(path) == expected_sha256, "Input SHA-256 mismatch")
    return path.resolve()


def time_left(deadline):
    remaining = deadline - time.monotonic()
    require(remaining > 0, "Verification deadline expired")
    return remaining


def run_tool(command, output, label, deadline, allowed_codes):
    """Capture logs and kill the entire decoder process group on timeout."""
    remaining = time_left(deadline)
    with (output / (label + "-stdout.log")).open("wb") as stdout, \
            (output / (label + "-stderr.log")).open("wb") as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            code = process.wait(timeout=remaining)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)
            raise
    require(code in allowed_codes, f"{label} failed with exit {code}; see captured logs")
    return code


def relocated_snapshots(value, old, new):
    """Only snapshotPath metadata changes; all semantic profile evidence is retained."""
    if isinstance(value, dict):
        return {key: new if key == "snapshotPath" and item == old
                else relocated_snapshots(item, old, new) for key, item in value.items()}
    if isinstance(value, list):
        return [relocated_snapshots(item, old, new) for item in value]
    return value


def archive_audit(source, destination, load_audit):
    before = load_audit(source)
    old = before[0]["profileInput"]["snapshotPath"]
    new = str(destination / "input-profile.prof")
    destination.mkdir(mode=0o700)
    file_hashes = {}
    for path in sorted(source.iterdir()):
        require(path.is_file() and not path.is_symlink(), "Unexpected mapper output entry")
        target = destination / path.name
        if path.suffix == ".json":
            value = json.loads(path.read_text())
            relocated = relocated_snapshots(value, old, new)
            if value != relocated:
                target.write_text(json.dumps(relocated, indent=2) + "\n")
            else:
                shutil.copyfile(path, target)
        else:
            shutil.copyfile(path, target)
        target.chmod(0o600)
        file_hashes[path.name] = {"originalSha256": digest(path), "archivedSha256": digest(target)}
    after = load_audit(destination)
    expected_proof = copy.deepcopy(before[0])
    expected_proof["profileInput"]["snapshotPath"] = new
    require(after[0] == expected_proof and after[1:] == before[1:],
            "Archive altered validated profile evidence")
    return {"originalDirectory": str(source), "archivedDirectory": str(destination),
            "snapshotPathFrom": old, "snapshotPathTo": new, "files": file_hashes}


def checked_audit(load_audit, folder, apk_sha256, kind, profile_sha256=None):
    audit = load_audit(folder)
    proof = audit[0]
    require(proof["apkSha256"] == apk_sha256, "Audit refers to an unexpected APK")
    require(proof["profileInput"]["kind"] == kind, "Audit refers to an unexpected profile input")
    if profile_sha256 is not None:
        require(proof["profileInput"]["sha256"] == profile_sha256,
                "Audit refers to an unexpected reference profile")
    return audit


def validate_profiles(tool_root, apk, apk_sha256, reference_profile,
                      reference_sha256, output_dir, timeout_seconds=180):
    require(math.isfinite(timeout_seconds) and timeout_seconds > 0, "Deadline must be finite and positive")
    deadline = time.monotonic() + timeout_seconds
    apk = checked_input(apk, apk_sha256, 64 * 1024 * 1024)
    reference = checked_input(reference_profile, reference_sha256, 1536 * 1024, profile=True)
    tool = Path(tool_root).resolve()
    output = Path(output_dir).resolve()
    # Check all inputs before reserving either output destination.
    executable_names = ("map_profile.py", "compare_profiles.py", "candidate_gate.py", "ProfileReadDump.java")
    require(all((tool / name).is_file() for name in executable_names), "Private profile tools are incomplete")
    tool_hashes = {name: digest(tool / name) for name in executable_names}
    # Preserve the supplied path for absolute-path and symlink/reuse checks.
    work = reserve_workspace(tool, Path(output_dir))
    time_left(deadline)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    report = {"status": "INCOMPLETE", "deviceOperations": 0, "workDirectory": str(work),
              "apkSha256": apk_sha256, "referenceSha256": reference_sha256,
              "toolSha256": tool_hashes, "archive": {}}
    report_path = output / "profile-validation.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    try:
        load_audit = runpy.run_path(str(tool / "compare_profiles.py"))["load_audit"]
        codes, audits = {}, {}
        for label, profile in (("bundled", None), ("reference", reference)):
            command = [sys.executable, "-B", str(tool / "map_profile.py"), "--apk", str(apk),
                       "--sha256", apk_sha256, "--output-dir", str(work / label)]
            if profile is not None:
                command += ["--binary-profile", str(profile)]
            codes[label] = run_tool(command, output, label, deadline, {0, 1})
            # Exit 1 may mean the old six-symbol coverage checklist failed. It is
            # never accepted without the complete decoder, DEX and inventory proof.
            audits[label] = checked_audit(load_audit, work / label, apk_sha256,
                                          "apk_bundled_profile" if profile is None else "provided_art_profile",
                                          reference_sha256 if profile is not None else None)
        command = [sys.executable, "-B", str(tool / "compare_profiles.py"),
                   "--bundled-audit", str(work / "bundled"),
                   "--reference-audit", str(work / "reference"), "--output-dir", str(work / "comparison")]
        codes["comparison"] = run_tool(command, output, "comparison", deadline, {0})
        equality = json.loads((work / "comparison" / "profile-set-comparison.json").read_text())
        require(equality.get("apkSha256") == apk_sha256 and all(equality.get(key) is True for key in
                ("setEquivalent", "dexChecksumSetsEquivalent", "methodRowSetEquivalent", "classRowSetEquivalent")),
                "Full profile inventories or DEX checksums differ")
        for label in ("bundled", "reference"):
            time_left(deadline)
            report["archive"][label] = archive_audit(work / label, output / label, load_audit)
        shutil.copytree(work / "comparison", output / "comparison")
        checked_input(apk, apk_sha256, 64 * 1024 * 1024)
        checked_input(reference, reference_sha256, 1536 * 1024, profile=True)
        require(tool_hashes == {name: digest(tool / name) for name in executable_names},
                "Profile tools changed during verification")
        time_left(deadline)
        report.update({"status": "VERIFIED", "exitCodes": codes,
                       "fullInventoryEquivalent": True,
                       "profileMethods": len(audits["reference"][2]),
                       "profileClasses": len(audits["reference"][3]),
                       "legacyCoverageGatePassed": {label: audits[label][0]["candidateCoverageGatePassed"]
                                                    for label in ("bundled", "reference")},
                       "limitation": equality["limitation"]})
    except BaseException as error:
        report.update({"status": "FAILED", "errorType": type(error).__name__, "message": str(error)[:512]})
        raise
    finally:
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        for path in (output, *output.rglob("*")):
            path.chmod(0o700 if path.is_dir() else 0o600)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("tool-root", "apk", "reference-profile", "output-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--reference-sha256", required=True)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    args = parser.parse_args()
    try:
        report = validate_profiles(args.tool_root, args.apk, args.sha256, args.reference_profile,
                                   args.reference_sha256, args.output_dir, args.timeout_seconds)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"status": "FAILED", "errorType": type(error).__name__, "message": str(error)[:512]}),
              file=sys.stderr)
        return 2
    print(json.dumps({key: report[key] for key in
                      ("status", "deviceOperations", "profileMethods", "profileClasses", "legacyCoverageGatePassed")},
                     indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
