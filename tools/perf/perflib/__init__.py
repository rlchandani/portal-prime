"""Hearthpane on-device benchmark harness (standard library only; drives `adb`, `fleetctl` and `gh`)."""

import os

PACKAGE = "com.immortal.launcher.debug"
HOME_COMPONENT = f"{PACKAGE}/com.immortal.launcher.HomeActivity"
RELEASE_REPO = "rlchandani/portal-prime"
RELEASE_APK_ASSET = "hearthpane.apk"
RELEASE_MANIFEST_ASSET = "hearthpane-latest.json"

WORKSPACE = "/Volumes/xHome/Github/portal-workspace"
DEFAULT_FLEETCTL = os.path.join(WORKSPACE, "portal-main", "provisioning", "fleetctl")
DEFAULT_RESULTS_ROOT = os.path.join(WORKSPACE, "scratch-tmp", "perf-results")
DEFAULT_QUEUE_ROOT = os.path.join(WORKSPACE, "scratch-tmp", "perf-queue")
DEFAULT_CACHE = os.path.join(os.path.expanduser("~"), ".cache", "portal-perf")
LOCK_ROOT = "/tmp"

# Session notes per Portal (the orchestrator reads these); unknown serials get their own file.
NOTES_FILES = {
    "2221B01C7T01C0": "/tmp/portal-lock-notes-family.txt",
    "822LCM04Z0101413": "/tmp/portal-lock-notes-mini.txt",
    "2221B01D2Z0503": "/tmp/portal-lock-notes-living.txt",
}


def notes_file(serial):
    return NOTES_FILES.get(serial, f"/tmp/portal-lock-notes-{serial}.txt")
