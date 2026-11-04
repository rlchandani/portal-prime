#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from portal_settings_fingerprint import (FingerprintError, load_response,
                                         revision, verify_response)


class FingerprintTests(unittest.TestCase):
    def test_literal_android_canonical_format(self):
        expected = '{"domains":{"empty":{}, "photo":{"enabled":true, "mode":"fit", "seconds":30}}, "layout":{"slots":[null, "app:org.example\\/.Home"]}}'
        self.assertEqual(
            revision({"photo": {"seconds": 30, "mode": "fit", "enabled": True}, "empty": {}},
                     {"slots": [None, "app:org.example/.Home"]}),
            hashlib.sha256(expected.encode("utf-8")).hexdigest())

    def test_android_string_escaping_and_unicode(self):
        expected = '{"domains":{"name":"Café\\/\\n\\t\\b\\r\\f\\u0001\\"\\\\"}, "layout":{}}'
        self.assertEqual(revision({"name": 'Café/\n\t\b\r\f\x01"\\'}, {}),
                         hashlib.sha256(expected.encode("utf-8")).hexdigest())

    def test_android_sorts_keys_as_utf16(self):
        expected = '{"domains":{"😀":1, "\ue000":2}, "layout":{}}'
        self.assertEqual(revision({"\ue000": 2, "😀": 1}, {}),
                         hashlib.sha256(expected.encode("utf-8")).hexdigest())

    def test_object_order_ignored_but_array_order_matters(self):
        self.assertEqual(revision({"b": 2, "a": 1}, {}), revision({"a": 1, "b": 2}, {}))
        self.assertNotEqual(revision({}, {"a": [1, 2]}), revision({}, {"a": [2, 1]}))

    def test_added_default_changes_revision_without_layout_change(self):
        self.assertNotEqual(revision({"immortal": {}}, {}),
                            revision({"immortal": {"stayInStartupApp": False}}, {}))

    def response(self):
        return {"ok": True, "snapshot": {"schemaVersion": 1, "domains": {},
                                        "layout": {}, "revision": revision({}, {})}}

    def test_matching_snapshot_and_changed_payload(self):
        response = self.response()
        self.assertTrue(verify_response(response)["matches"])
        response["snapshot"]["layout"]["slots"] = [None]
        result = verify_response(response)
        self.assertFalse(result["matches"])
        self.assertEqual(set(result), {"matches", "expectedRevision", "computedRevision"})

    def test_invalid_response_shapes_and_revision(self):
        for value in (None, {}, {"ok": False}, {"ok": True, "snapshot": []}):
            with self.subTest(value=value), self.assertRaises(FingerprintError):
                verify_response(value)
        for field, value in (("schemaVersion", True), ("schemaVersion", 2),
                             ("domains", []), ("layout", None), ("revision", "secret")):
            response = self.response()
            response["snapshot"][field] = value
            with self.subTest(field=field), self.assertRaises(FingerprintError):
                verify_response(response)

    def test_rejects_numbers_outside_existing_integer_wire_contract(self):
        for value in (1.0, float("nan"), float("inf"), 2**63, -(2**63)-1):
            with self.subTest(value=value), self.assertRaisesRegex(FingerprintError, "^unsupported_value$"):
                revision({"value": value}, {})
        self.assertEqual(len(revision({"min": -(2**63), "max": 2**63-1}, {})), 64)

    def test_rejects_non_json_library_values_and_invalid_keys(self):
        for value in ({1: "value"}, {"value": object()}, {"value": "\ud800"}):
            with self.subTest(value=value), self.assertRaises(FingerprintError):
                revision(value, {})

    def test_loader_rejects_duplicate_keys_and_nonfinite_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.json"
            for text in ('{"secret":1,"secret":2}', '{"secret":NaN}', '{"secret":'):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(FingerprintError) as caught:
                    load_response(path)
                self.assertNotIn("secret", str(caught.exception))

    def test_cli_match_mismatch_and_safe_error(self):
        script = Path(__file__).with_name("portal_settings_fingerprint.py")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture.json"
            response = self.response()
            for change, expected_code in ((False, 0), (True, 1)):
                if change:
                    response["snapshot"]["layout"] = {"slots": [None]}
                path.write_text(json.dumps(response))
                result = subprocess.run([sys.executable, "-B", str(script), str(path)],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, expected_code, result.stderr)
                self.assertEqual(json.loads(result.stdout)["matches"], not change)
            path.write_text('{"private-value":')
            result = subprocess.run([sys.executable, "-B", str(script), str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn("private-value", result.stdout + result.stderr)
            self.assertNotIn(str(path), result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
