#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""A loopback stand-in for a Portal's sealed backup routes, for the provisioning tests.

It follows the agent's wire format (FleetBackupRoutes / FleetBackupCrypto / FleetBackupArchive):
`/backup/key` first-use enrolment, `/backup/export` sealed to the enrolled key, and the two-step
`/backup/import` with a one-time RSA key. Synthetic data only.
"""

import base64
import gzip
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import threading
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


OAEP = padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)
SECRET_MARKERS = ("synthetic-household-key-0123", "synthetic-phone-session-4567", "synthetic-ha-token-89ab")


def b64(data):
    return base64.b64encode(data).decode()


def key_id(spki):
    return hashlib.sha256(spki).hexdigest()[:16]


def spki_of(public_key):
    return public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


class FakeSealedPortal:
    def __init__(self, token, device="0123456789abcdef0123456789abcdef"):
        self.token = token
        self.device = device
        self.enrolled = None
        self.pending = None
        self.prefs = {
            "immortal_screensaver": {"dark_room_lux": ["i", 7], "face": ["s", "flip"]},
            "immortal_household": {"k": ["s", SECRET_MARKERS[0]]},
            "remote_pairing": {"sessions": ["ss", [SECRET_MARKERS[1]]]},
            "immortal_ha": {"token": ["s", SECRET_MARKERS[2]]},
        }
        self.files = {"files/household_list.json": b64(b'{"items":["milk"]}')}
        self.household = {"screensaver.shuffle": True}
        self.applied_household = []
        self.sessions = {}
        self.calls = []
        self.override = {}  # path -> (status, body): a canned reply
        self.tamper_export = False
        self.export_key = None  # seal exports to this public key instead of the enrolled one
        self.last_call = {}  # (path, method, op) -> time, for the agent's per-route minimum gap
        self.rate_limited = 0
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def paths(self):
        return [path for path, _ in self.calls]

    def _handler(self):
        portal = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self._serve("GET")

            def do_POST(self):
                self._serve("POST")

            def _serve(self, method):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                body = json.loads(raw) if raw else {}
                portal.calls.append((self.path, body.get("op") if isinstance(body, dict) else None))
                if self.headers.get("Authorization") != "Bearer " + portal.token and self.path != "/__usb_confirm":
                    status, reply = 401, {"ok": False, "error": "unauthorized"}
                elif portal.too_soon(self.path, method, body):
                    status, reply = 429, {"ok": False, "error": "backup_rate_limited", "retryAfterSec": 2}
                elif self.path in portal.override:
                    status, reply = portal.override[self.path]
                else:
                    status, reply = portal.route(method, self.path, body)
                data = json.dumps(reply).encode() if not isinstance(reply, bytes) else reply
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        return Handler

    def too_soon(self, path, method, body):
        """The agent's 2 s minimum gap per route (an import's begin and apply are separate)."""
        if not path.startswith("/backup/"):
            return False
        key = (path, method, body.get("op") if isinstance(body, dict) else None)
        now = time.monotonic()
        if now - self.last_call.get(key, -10.0) < 2.0:
            self.rate_limited += 1
            return True
        self.last_call[key] = now
        return False

    def usb_confirm(self, wanted):
        """What the DUMP-guarded adb receiver does."""
        if self.enrolled is None and self.pending is not None and key_id(self.pending) == wanted:
            self.enrolled, self.pending = self.pending, None

    def route(self, method, path, body):
        if path == "/__usb_confirm":  # test-only side door standing in for `adb shell am broadcast`
            self.calls.pop()
            self.usb_confirm(body.get("keyId"))
            return 200, {"ok": True}
        if path == "/backup/key" and method == "POST":
            spki = base64.b64decode(body["publicKey"])
            key = serialization.load_der_public_key(spki)
            if not isinstance(key, rsa.RSAPublicKey) or key.key_size < 3072:
                return 400, {"ok": False, "error": "bad_public_key_size"}
            if self.enrolled is None:
                self.pending = spki
                return 202, {"ok": True, "result": "pending", "keyId": key_id(spki), "confirm": "usb"}
            if self.enrolled == spki:
                result = "unchanged"
            else:
                return 409, {"ok": False, "error": "backup_key_mismatch", "keyId": key_id(self.enrolled)}
            return 200, {"ok": True, "result": result, "keyId": key_id(spki)}
        if path == "/backup/export" and method == "POST":
            if self.enrolled is None:
                return 409, {"ok": False, "error": "backup_key_not_enrolled"}
            if body.get("keyId") not in (None, key_id(self.enrolled)):
                return 409, {"ok": False, "error": "backup_key_mismatch", "keyId": key_id(self.enrolled)}
            return 200, self.export()
        if path == "/backup/import" and method == "POST":
            if body.get("op") == "begin":
                private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
                session = os.urandom(16).hex()
                self.sessions[session] = private
                return 200, {"ok": True, "session": session, "publicKey": b64(spki_of(private.public_key())),
                             "expiresInSec": 300}
            if body.get("op") == "apply":
                return self.apply(body)
        return 404, {"ok": False, "error": "not_found"}

    def archive(self):
        return {"format": "hearthpane-backup", "version": 1, "device": self.device, "createdAt": 1763600000000,
                "app": {"versionCode": 269, "versionName": "1.275"}, "prefs": self.prefs,
                "files": self.files, "household": self.household}

    def export(self):
        recipient = self.export_key or serialization.load_der_public_key(self.enrolled)
        header = json.dumps({"format": "hearthpane-sealed-backup", "version": 1, "alg": "RSA-OAEP-256/A256GCM/gzip",
                             "keyId": key_id(self.enrolled), "device": self.device, "createdAt": 1763600000000,
                             "versionCode": 269}).encode()
        data_key, nonce = os.urandom(32), os.urandom(12)
        ciphertext = bytearray(AESGCM(data_key).encrypt(nonce, gzip.compress(json.dumps(self.archive()).encode()), header))
        if self.tamper_export:
            ciphertext[5] ^= 1
        envelope = {"format": "hearthpane-sealed-backup", "version": 1, "header": b64(header),
                    "wrappedKey": b64(recipient.encrypt(data_key, OAEP)), "nonce": b64(nonce),
                    "ciphertext": b64(bytes(ciphertext))}
        return {"ok": True, "keyId": key_id(self.enrolled), "device": self.device, "backup": envelope,
                "summary": {"prefFiles": len(self.prefs), "dataFiles": len(self.files),
                            "householdSettings": len(self.household)}}

    def apply(self, body):
        private = self.sessions.pop(body.get("session"), None)
        if private is None:
            return 409, {"ok": False, "error": "import_session_unknown"}
        envelope = body["backup"]
        header = base64.b64decode(envelope["header"])
        try:
            data_key = private.decrypt(base64.b64decode(envelope["wrappedKey"]), OAEP)
            packed = AESGCM(data_key).decrypt(base64.b64decode(envelope["nonce"]),
                                             base64.b64decode(envelope["ciphertext"]), header)
        except Exception:
            return 422, {"ok": False, "error": "seal_broken"}
        archive = json.loads(gzip.decompress(packed))
        cross = body.get("crossDevice") is True
        if not cross and archive["device"] != self.device and body.get("sameSerial") is not True:
            return 409, {"ok": False, "error": "different_device"}
        prefs, files, household = ({}, {}, archive["household"]) if cross else (archive["prefs"], archive["files"], {})
        plan = {"prefFiles": len(prefs), "prefValues": sum(len(v) for v in prefs.values()),
                "dataFiles": sorted(files), "householdSettings": len(household), "skipped": []}
        reply = {"ok": True, "sameDevice": archive["device"] == self.device, "plan": plan}
        if body.get("dryRun") is True:
            return 200, dict(reply, dryRun=True)
        self.prefs.update(prefs)
        self.files.update(files)
        if household:
            self.applied_household.append(household)
        return 200, dict(reply, dryRun=False, rebootRecommended=True,
                         applied={"prefFiles": len(prefs), "dataFiles": len(files), "householdSettings": len(household)})


FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys, urllib.request
ports = json.loads(os.environ.get("FAKE_ADB_PORTS", "{}"))
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_CALLS"], "a") as stream:
    stream.write(json.dumps(args) + "\n")
if len(args) == 3 and args[0] == "-s" and args[2] == "get-state":
    if args[1] in ports:
        print("device")
        sys.exit(0)
    sys.exit(1)
if len(args) > 2 and args[0] == "-s" and args[1] in ports and args[2:6] == ["shell", "am", "broadcast", "-a"]:
    if args[6] == "com.immortal.launcher.action.CONFIRM_BACKUP_KEY" and "-p" in args:
        key = args[args.index("keyId") + 1]
        request = urllib.request.Request("http://127.0.0.1:%d/__usb_confirm" % ports[args[1]],
                                         data=json.dumps({"keyId": key}).encode(), method="POST")
        urllib.request.urlopen(request).read()
    sys.exit(0)
sys.exit(1)
'''
