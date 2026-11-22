"""Builds under test: local APKs (optionally with a .dm) and the latest official release.

Installs are plain `adb install -r` (`install-multiple -r` with a .dm); `-d` is added only when
Android refuses a version downgrade. Never force-stopped, never uninstalled. Every install is
verified by comparing the on-device base.apk sha256 with the file we installed.
"""

import hashlib
import json
import os
import re
from dataclasses import dataclass, replace
from typing import Optional

from . import DEFAULT_CACHE, PACKAGE, RELEASE_APK_ASSET, RELEASE_MANIFEST_ASSET, RELEASE_REPO
from .proc import Runner

SHA256 = re.compile(r"^[0-9a-f]{64}$")
INSTALL_TIMEOUT_S = 300


class BuildError(Exception):
    pass


@dataclass(frozen=True)
class Build:
    label: str
    kind: str  # "official" | "apk"
    apk: str
    dm: Optional[str] = None
    sha256: str = ""
    version_name: str = ""
    tag: str = ""

    def as_dict(self):
        return {"label": self.label, "kind": self.kind, "apk": self.apk, "dm": self.dm, "sha256": self.sha256,
                "versionName": self.version_name, "tag": self.tag}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_apk_spec(spec):
    """'A.apk' or 'A.apk,A.dm' -> (apk, dm)."""
    apk, _, dm = spec.partition(",")
    return os.path.abspath(apk), (os.path.abspath(dm) if dm else None)


def local_build(label, spec):
    apk, dm = parse_apk_spec(spec)
    if not os.path.isfile(apk) or not apk.endswith(".apk"):
        raise BuildError(f"not an APK file: {apk}")
    if dm and (not os.path.isfile(dm) or not dm.endswith(".dm")):
        raise BuildError(f"not a .dm file: {dm}")
    return Build(label, "apk", apk, dm, sha256_file(apk))


def parse_manifest(text):
    try:
        m = json.loads(text)
        code, name, sha = int(m["versionCode"]), str(m["versionName"]), str(m["sha256"]).lower()
    except (ValueError, KeyError, TypeError):
        raise BuildError("release manifest unreadable") from None
    if m.get("packageName") != PACKAGE or not SHA256.match(sha) or code <= 0:
        raise BuildError("release manifest failed validation")
    return {"versionCode": code, "versionName": name, "sha256": sha}


class OfficialSource:
    """The GitHub "Latest" (never a prerelease) Hearthpane, downloaded once per tag and sha-verified."""

    def __init__(self, runner=None, repo=RELEASE_REPO, cache=DEFAULT_CACHE):
        self.runner = runner or Runner()
        self.repo = repo
        self.cache = cache
        self._build = None

    def _gh(self, args, timeout=120):
        p = self.runner.run(["gh", *args], timeout=timeout)
        if p.rc != 0:
            raise BuildError(f"gh {' '.join(args[:2])} failed (rc {p.rc})")
        return p.out

    def latest(self, label="O"):
        if self._build is not None:
            return replace(self._build, label=label)
        meta = json.loads(self._gh(["release", "view", "--repo", self.repo, "--json", "tagName,isPrerelease,isDraft"]))
        if meta.get("isPrerelease") or meta.get("isDraft"):
            raise BuildError("the latest release is not an official release")
        tag = meta["tagName"]
        manifest = parse_manifest(self._gh(["release", "download", tag, "--repo", self.repo,
                                            "-p", RELEASE_MANIFEST_ASSET, "-O", "-"]))
        folder = os.path.join(self.cache, "official", tag)
        apk = os.path.join(folder, RELEASE_APK_ASSET)
        if not (os.path.isfile(apk) and sha256_file(apk) == manifest["sha256"]):
            os.makedirs(folder, exist_ok=True)
            self._gh(["release", "download", tag, "--repo", self.repo, "-p", RELEASE_APK_ASSET, "-D", folder,
                      "--clobber"], timeout=600)
        got = sha256_file(apk) if os.path.isfile(apk) else ""
        if got != manifest["sha256"]:
            raise BuildError(f"official {tag} sha256 mismatch ({got[:12]} vs {manifest['sha256'][:12]})")
        self._build = Build(label, "official", apk, None, got, manifest["versionName"], tag)
        return self._build


def install(device, build, log=print):
    """Installs [build] over the running app (no force-stop) and verifies its on-device sha256."""
    base = ["install-multiple", "-r", build.apk, build.dm] if build.dm else ["install", "-r", build.apk]
    p = device.adb(base, timeout=INSTALL_TIMEOUT_S)
    text = p.out + p.err
    if "INSTALL_FAILED_VERSION_DOWNGRADE" in text:
        log(f"  {build.label}: version downgrade refused, retrying with -d")
        p = device.adb([base[0], "-r", "-d", *base[2:]], timeout=INSTALL_TIMEOUT_S)
        text = p.out + p.err
    if p.rc != 0 or "Success" not in text:
        tail = [x for x in text.strip().splitlines() if x.strip()][-1:] or ["no output"]
        raise BuildError(f"install of {build.label} failed: {tail[0][:200]}")
    on_device = device.installed_sha()
    if on_device != build.sha256:
        raise BuildError(f"{build.label}: on-device sha256 {on_device[:12] or '?'} != {build.sha256[:12]}")
    return on_device
