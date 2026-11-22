#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline checks for the opt-in Meta debloat lists in config.env and the shell guard."""

from pathlib import Path
import re
import shlex
import subprocess
import unittest

HERE = Path(__file__).resolve().parent
CONFIG = HERE / "config.env"
SCRIPT = HERE / "provision.sh"

# What Messenger/WhatsApp calling and Hearthpane need (docs/guides/portal-debloat.md).
CALLING_CRITICAL = {
    "com.facebook.portal.aiservice",
    "com.facebook.alohasdk.virtualcameramanager",
    "com.facebook.alohaservices.presence",
    "com.facebook.alohaservices.alohausers",
    "com.facebook.aloha.system.device",
    "com.facebook.aloha.system.services",
    "com.facebook.alohaapps.launcher",
    "com.facebook.alohaapps.contacts",
    "com.facebook.aloha.app.messenger",
    "com.facebook.aloha.app.whatsapp",
    "com.facebook.alohaservices.abilitymanager",
    "com.facebook.alohaappmanager",
    "com.facebook.alohasdk.pushnotification",
    "com.facebook.aloha.app.ttsservice",
    "com.facebook.portal.webview",
    "com.facebook.alohasdk.bluetooth",
    "com.facebook.alohasdk.location",
    "com.facebook.alohaapps.settings",
    "com.facebook.alohaapps.controlcenter",
}


def config_value(key):
    for line in CONFIG.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return " ".join(shlex.split(line.split("=", 1)[1]))
    raise AssertionError(f"{key} missing from config.env")


def config_list(key):
    return config_value(key).split()


class DebloatConfigTest(unittest.TestCase):
    def test_lists_have_no_duplicates(self):
        for key in ("DEBLOAT_PACKAGES", "DEBLOAT_PROTECTED"):
            items = config_list(key)
            self.assertEqual(len(items), len(set(items)), key)

    def test_disabled_and_protected_are_disjoint(self):
        overlap = set(config_list("DEBLOAT_PACKAGES")) & set(config_list("DEBLOAT_PROTECTED"))
        self.assertEqual(overlap, set())

    def test_calling_critical_packages_are_protected(self):
        missing = CALLING_CRITICAL - set(config_list("DEBLOAT_PROTECTED"))
        self.assertEqual(missing, set())

    def test_debloat_does_not_own_other_features_packages(self):
        # --undebloat re-enables the whole list, so it must not include packages that other
        # provisioning steps disable on purpose (OTA block, verifier, presence opt-out).
        others = set(config_list("OTA_PACKAGES")) | {
            config_value("VERIFIER_PKG"), config_value("PRESENCE_PKG")}
        self.assertEqual(set(config_list("DEBLOAT_PACKAGES")) & others, set())

    def test_only_meta_packages_are_listed(self):
        for pkg in config_list("DEBLOAT_PACKAGES"):
            self.assertRegex(pkg, r"^com\.facebook\.[a-z0-9_.]+$")


class DebloatGuardTest(unittest.TestCase):
    def guard(self, pkg):
        source = SCRIPT.read_text(encoding="utf-8")
        line = next(l for l in source.splitlines() if l.startswith("debloat_protected()"))
        protected = config_value("DEBLOAT_PROTECTED")
        script = f"DEBLOAT_PROTECTED={shlex.quote(protected)}\n{line}\ndebloat_protected {shlex.quote(pkg)}"
        return subprocess.run(["bash", "-c", script], check=False).returncode

    def test_guard_refuses_every_protected_package(self):
        for pkg in config_list("DEBLOAT_PROTECTED"):
            self.assertEqual(self.guard(pkg), 0, pkg)

    def test_guard_allows_listed_packages(self):
        for pkg in config_list("DEBLOAT_PACKAGES"):
            self.assertEqual(self.guard(pkg), 1, pkg)

    def test_guard_matches_whole_names_only(self):
        self.assertEqual(self.guard("com.facebook.aloha.app.messenge"), 1)
        self.assertEqual(self.guard("com.facebook.aloha.app.messenger.extra"), 1)


if __name__ == "__main__":
    unittest.main()
