#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Exercise audit pagination through the real client with an inert transport."""
import contextlib
import io
import json
import os
import sys
import unittest
from unittest.mock import patch

import immich_audit


class FixtureTransport:
    """Finite responses: an extra request fails before any network access."""

    def __init__(self, steps):
        self.steps = steps
        self.requests = []
        self.responses = []

    def __call__(self, request, *, timeout, context):
        index = len(self.requests)
        body = json.loads(request.data) if request.data is not None else None
        actual = (request.get_method(), request.full_url, body)
        self.requests.append(actual)
        if index >= len(self.steps):
            raise AssertionError(f"unexpected extra fixture request: {actual}")
        method, path, expected_body, payload = self.steps[index]
        expected = (method, "https://fixture.invalid" + path, expected_body)
        if actual != expected:
            raise AssertionError(f"fixture request mismatch: {actual} != {expected}")
        if timeout != 20 or context is not None:
            raise AssertionError("fixture expects the normal TLS/timeout defaults")
        response = io.BytesIO(json.dumps(payload).encode())
        self.responses.append(response)
        return response


def search_step(page, items, next_page=None, album=None):
    body = {"size": 1000, "page": page}
    if album:
        body["albumIds"] = [album]
    assets = {"items": items}
    if next_page is not None:
        assets["nextPage"] = next_page
    return ("POST", "/api/search/metadata", body, {"assets": assets})


class ImmichAuditPaginationTest(unittest.TestCase):
    def client(self):
        return immich_audit.Immich("https://fixture.invalid", "host-fixture-key", False)

    def assert_closed(self, transport, count):
        self.assertEqual(count, len(transport.requests))
        self.assertEqual(count, len(transport.responses))
        self.assertTrue(all(response.closed for response in transport.responses))

    def test_repeated_first_page_fails_before_another_request(self):
        transport = FixtureTransport([
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}], "1"),
        ])
        with patch("urllib.request.urlopen", transport):
            with self.assertRaisesRegex(ValueError, "advance"):
                self.client().enumerate_assets(None)
        self.assert_closed(transport, 1)

    def test_backward_page_fails_before_another_request(self):
        transport = FixtureTransport([
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}], "2"),
            search_step(2, [{"id": "video-b", "type": "VIDEO"}], "1"),
        ])
        with patch("urllib.request.urlopen", transport):
            with self.assertRaisesRegex(ValueError, "advance"):
                self.client().enumerate_assets(None)
        self.assert_closed(transport, 2)

    def test_cli_exits_one_without_reporting_a_partial_library(self):
        transport = FixtureTransport([
            ("GET", "/api/users/me", None, {}),
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}], "2"),
            search_step(2, [{"id": "video-b", "type": "VIDEO"}], "2"),
        ])
        stdout, stderr = io.StringIO(), io.StringIO()
        argv = ["immich_audit.py", "--url", "https://fixture.invalid",
                "--key", "host-fixture-key"]
        with patch.dict(os.environ, {}, clear=True), patch.object(sys, "argv", argv), \
                patch("urllib.request.urlopen", transport), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as exit_result:
                immich_audit.main()
        self.assertEqual(1, exit_result.exception.code)
        self.assertIn("advance", stderr.getvalue())
        self.assertNotIn("Found", stderr.getvalue())
        self.assertEqual("", stdout.getvalue())
        self.assert_closed(transport, 3)

    def test_advancing_sparse_page_keeps_album_scope_and_asset_types(self):
        transport = FixtureTransport([
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}], "4", "album-a"),
            search_step(4, [{"id": "video-b", "type": "VIDEO"}, {"id": "other-c"}],
                        album="album-a"),
        ])
        with patch("urllib.request.urlopen", transport):
            result = self.client().enumerate_assets("album-a")
        self.assertEqual([("photo-a", "IMAGE"), ("video-b", "VIDEO"), ("other-c", None)],
                         result)
        self.assert_closed(transport, 2)

    def test_empty_page_stops_even_with_a_repeated_marker(self):
        transport = FixtureTransport([search_step(1, [], "1")])
        with patch("urllib.request.urlopen", transport):
            self.assertEqual([], self.client().enumerate_assets(None))
        self.assert_closed(transport, 1)

    def test_missing_marker_keeps_the_final_nonempty_page(self):
        transport = FixtureTransport([
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}]),
        ])
        with patch("urllib.request.urlopen", transport):
            self.assertEqual([("photo-a", "IMAGE")], self.client().enumerate_assets(None))
        self.assert_closed(transport, 1)

    def test_malformed_marker_retains_existing_stop_behavior(self):
        transport = FixtureTransport([
            search_step(1, [{"id": "photo-a", "type": "IMAGE"}], "not-a-page"),
        ])
        with patch("urllib.request.urlopen", transport):
            self.assertEqual([("photo-a", "IMAGE")], self.client().enumerate_assets(None))
        self.assert_closed(transport, 1)


if __name__ == "__main__":
    unittest.main()
