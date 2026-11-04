#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Verify saved /remote/snapshot responses without contacting a Portal.

Matches RemoteSnapshotPolicy.revision on Android: UTF-16 key order,
comma-space separators, compact colons, literal Unicode and escaped slashes.
Only the existing settings/layout wire types are supported: objects, arrays,
strings, booleans, null and signed 64-bit integers. No float coercion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


class FingerprintError(ValueError):
    """A fixed diagnostic code, never captured settings or file contents."""


def _quote(value):
    try:
        value.encode("utf-8")
    except UnicodeError:
        raise FingerprintError("invalid_unicode") from None
    return json.dumps(value, ensure_ascii=False).replace("/", r"\/")


def _canonical(value):
    if type(value) is dict:
        if any(type(key) is not str for key in value):
            raise FingerprintError("invalid_object_key")
        keys = sorted(value, key=lambda key: key.encode("utf-16-be", "surrogatepass"))
        return "{" + ", ".join(_quote(key) + ":" + _canonical(value[key]) for key in keys) + "}"
    if type(value) is list:
        return "[" + ", ".join(_canonical(item) for item in value) + "]"
    if type(value) is str:
        return _quote(value)
    if value is None:
        return "null"
    if type(value) is bool:
        return "true" if value else "false"
    if type(value) is int and -(2**63) <= value < 2**63:
        return str(value)
    raise FingerprintError("unsupported_value")


def revision(domains, layout):
    """Hash explicit settings domains and layout; does not mutate either."""
    if type(domains) is not dict or type(layout) is not dict:
        raise FingerprintError("invalid_snapshot_objects")
    try:
        encoded = _canonical({"domains": domains, "layout": layout}).encode("utf-8")
    except RecursionError:
        raise FingerprintError("invalid_nesting") from None
    return hashlib.sha256(encoded).hexdigest()


def verify_response(response):
    """Return fingerprint-only evidence for a schema-v1 API response."""
    if type(response) is not dict or response.get("ok") is not True:
        raise FingerprintError("invalid_response")
    snapshot = response.get("snapshot")
    if type(snapshot) is not dict or type(snapshot.get("schemaVersion")) is not int or snapshot["schemaVersion"] != 1:
        raise FingerprintError("invalid_snapshot_schema")
    expected = snapshot.get("revision")
    if type(expected) is not str or re.fullmatch(r"[0-9a-f]{64}", expected) is None:
        raise FingerprintError("invalid_revision")
    computed = revision(snapshot.get("domains"), snapshot.get("layout"))
    return {"matches": computed == expected,
            "expectedRevision": expected, "computedRevision": computed}


def load_response(path):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise FingerprintError("duplicate_object_key")
            result[key] = value
        return result

    def constant(_value):
        raise FingerprintError("unsupported_value")

    try:
        return json.loads(Path(path).read_text(encoding="utf-8"),
                          object_pairs_hook=object_pairs, parse_constant=constant)
    except FingerprintError:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise FingerprintError("invalid_capture") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", help="locally saved /remote/snapshot JSON response")
    args = parser.parse_args(argv)
    try:
        result = verify_response(load_response(args.capture))
    except FingerprintError as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0 if result["matches"] else 1


if __name__ == "__main__":
    sys.exit(main())
