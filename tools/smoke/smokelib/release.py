"""The latest *official* Hearthpane release (GitHub "Latest", never a prerelease) and its manifest."""

import json
import re
import subprocess
from dataclasses import dataclass

from . import PACKAGE

MANIFEST_ASSET = "hearthpane-latest.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class Release:
    tag: str
    version_code: int
    version_name: str
    sha256: str


class ReleaseError(Exception):
    pass


def _gh(args, timeout=60):
    try:
        p = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise ReleaseError(f"gh failed: {type(e).__name__}") from None
    if p.returncode != 0:
        raise ReleaseError(f"gh {args[0]} {args[1]} failed (rc {p.returncode})")
    return p.stdout


def latest(repo):
    meta = json.loads(_gh(["release", "view", "--repo", repo, "--json", "tagName,isPrerelease,isDraft"]))
    if meta.get("isPrerelease") or meta.get("isDraft"):
        raise ReleaseError("the latest release is not an official release")
    tag = meta["tagName"]
    return parse_manifest(tag, _gh(["release", "download", tag, "--repo", repo, "-p", MANIFEST_ASSET, "-O", "-"]))


def parse_manifest(tag, text):
    """Validated manifest fields (schema 1). Raises ReleaseError on anything unexpected."""
    try:
        m = json.loads(text)
        code, name, sha = int(m["versionCode"]), str(m["versionName"]), str(m["sha256"]).lower()
    except (ValueError, KeyError, TypeError):
        raise ReleaseError("release manifest unreadable") from None
    if m.get("packageName") != PACKAGE or not SHA256.match(sha) or code <= 0:
        raise ReleaseError("release manifest failed validation")
    return Release(tag, code, name, sha)
