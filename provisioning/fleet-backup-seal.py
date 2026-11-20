#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Sealed Portal settings backups: the Mac's backup key, enrolment, export and import.

Hearthpane 1.275+ seals its preference export on the Portal (AES-256-GCM under a fresh data key,
wrapped with RSA-OAEP-256 to this Mac's backup public key), so only this Mac's private key opens
it. The private key lives in the macOS Keychain, or in a 0600 file when the Keychain isn't
available. A restore never writes the plaintext anywhere: the archive is opened in memory to
check it, then only its 32-byte data key is re-wrapped to a one-time key the Portal made for that
import, and the Portal opens, validates and applies it.

Commands (paths are a fleet registry record and a snapshot directory):
  export   <registry.json> <snapshot-dir>   enrol this Mac's key if needed, then save a sealed export
  inspect  <snapshot-dir>                   open the snapshot in memory; print counts only
  import   <registry.json> <snapshot-dir> [--cross-device | --same-serial] [--dry-run]
  key-id                                    print this Mac's backup key id (creating no key)

Never prints a token, a key or a preference value.
"""

import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import zlib


FORMAT = "hearthpane-sealed-backup"
ARCHIVE_FORMAT = "hearthpane-backup"
SNAPSHOT_FORMAT = "hearthpane-sealed-snapshot"
SNAPSHOT_NAME = "sealed-backup.json"
VERSION = 1
ALG = "RSA-OAEP-256/A256GCM/gzip"
KEYCHAIN_SERVICE = "hearthpane-fleet-backup"
KEYCHAIN_ACCOUNT = "backup-key-v1"
DEFAULT_PORT = 8723
MAX_REPLY_BYTES = 24 * 1024 * 1024
MAX_PLAIN_BYTES = 8 * 1024 * 1024
MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
RSA_BITS = 3072
TIMEOUT_SEC = 120
ERROR_CODE = re.compile(r"[a-z][a-z0-9_]{0,63}", re.ASCII)
HEX_ID = re.compile(r"[0-9a-f]{16}|[0-9a-f]{32}", re.ASCII)
SERIAL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*", re.ASCII)
PACKAGES = ("com.immortal.launcher", "com.immortal.launcher.debug")
CONFIRM_ACTION = "com.immortal.launcher.action.CONFIRM_BACKUP_KEY"
FORGET_ACTION = "com.immortal.launcher.action.FORGET_BACKUP_KEY"


class Failure(Exception):
    """A failure whose message is safe to print (no secrets, no device content)."""


def emit(text, stream):
    try:
        print(text, file=stream, flush=True)
    except OSError:
        # A closed progress pipe must not change the outcome: send the rest (and the exit
        # flush) to /dev/null instead.
        devnull = os.open(os.devnull, os.O_WRONLY)
        os.dup2(devnull, stream.fileno())
        os.close(devnull)


def say(text):
    emit(text, sys.stdout)


def complain(text):
    emit(text, sys.stderr)


def primitives():
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.exceptions import InvalidTag
    except ImportError:
        raise Failure("the Python 'cryptography' package is required: python3 -m pip install cryptography")
    oaep = padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)
    return serialization, rsa, AESGCM, oaep, InvalidTag


def unique_object(pairs):
    value = {}
    for key, entry in pairs:
        if key in value:
            raise ValueError("duplicate JSON field")
        value[key] = entry
    return value


def loads(data):
    return json.loads(data, object_pairs_hook=unique_object)


def b64(data):
    return base64.b64encode(data).decode("ascii")


def unb64(text, limit):
    if not isinstance(text, str) or len(text) > (limit // 3 + 1) * 4:
        raise Failure("the sealed backup is malformed")
    try:
        return base64.b64decode(text, validate=True)
    except ValueError:
        raise Failure("the sealed backup is malformed")


def key_id(spki):
    return hashlib.sha256(spki).hexdigest()[:16]


# --- this Mac's backup key ---------------------------------------------------------------

def key_file():
    configured = os.environ.get("IMMORTAL_BACKUP_KEY_FILE")
    if configured:
        return Path(configured)
    return Path.home() / ".config" / "hearthpane" / "fleet-backup-key"


def keychain_enabled():
    return (os.environ.get("IMMORTAL_BACKUP_KEYSTORE", "keychain") != "file"
            and sys.platform == "darwin" and shutil.which("security") is not None)


def keychain_read():
    result = subprocess.run(
        ["security", "find-generic-password", "-a", KEYCHAIN_ACCOUNT, "-s", KEYCHAIN_SERVICE, "-w"],
        capture_output=True, text=True, timeout=60)
    if result.returncode == 44:  # errSecItemNotFound
        return None
    if result.returncode != 0:
        raise Failure("the Keychain refused to read the backup key (is it locked?)")
    return result.stdout.strip()


def keychain_write(encoded):
    # Through stdin (`security -i`) so the key never appears in a process listing.
    command = ('add-generic-password -a %s -s %s -l "Hearthpane fleet backup key" -w %s\n'
               % (KEYCHAIN_ACCOUNT, KEYCHAIN_SERVICE, encoded))
    subprocess.run(["security", "-i"], input=command, capture_output=True, text=True, timeout=60)
    if keychain_read() != encoded:
        raise Failure("the Keychain did not store the backup key")


def file_read(path):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    with os.fdopen(descriptor, "r", encoding="ascii") as stream:
        status = os.fstat(stream.fileno())
        if not stat.S_ISREG(status.st_mode) or status.st_uid != os.geteuid() or status.st_mode & 0o077:
            raise Failure("the backup key file must be a private file you own (chmod 600)")
        return stream.read().strip()


def file_write(path, encoded):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii") as stream:
        stream.write(encoded + "\n")


def parse_private(encoded):
    serialization, rsa, _, _, _ = primitives()
    try:
        key = serialization.load_der_private_key(base64.b64decode(encoded, validate=True), password=None)
    except ValueError:
        raise Failure("the stored backup key is unreadable")
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < RSA_BITS:
        raise Failure("the stored backup key is not an RSA-3072 key")
    return key


def load_key(create):
    if keychain_enabled():
        encoded = keychain_read()
        if encoded:
            return parse_private(encoded)
    encoded = file_read(key_file())
    if encoded:
        return parse_private(encoded)
    if not create:
        raise Failure("this Mac has no fleet backup key; run fleet-backup.sh first")
    serialization, rsa, _, _, _ = primitives()
    key = rsa.generate_private_key(public_exponent=65537, key_size=RSA_BITS)
    encoded = b64(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()))
    if keychain_enabled():
        try:
            keychain_write(encoded)
            say("  created this Mac's backup key (stored in the login Keychain)")
            return key
        except (Failure, OSError, subprocess.SubprocessError):
            say("  note: the Keychain is unavailable; storing the backup key in a private file")
    file_write(key_file(), encoded)
    say("  created this Mac's backup key (stored in %s, mode 600)" % key_file())
    return key


def public_spki(key):
    serialization, _, _, _, _ = primitives()
    return key.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)


# --- the Portal ---------------------------------------------------------------------------

class Portal:
    def __init__(self, registry_path):
        try:
            record = loads(Path(registry_path).read_bytes().removeprefix(b"\xef\xbb\xbf"))
        except (OSError, ValueError):
            raise Failure("the registry record is unreadable")
        if not isinstance(record, dict) or not isinstance(record.get("token"), str) or not record["token"]:
            raise Failure("the registry record has no fleet token")
        self.serial = record.get("serial") if isinstance(record.get("serial"), str) else ""
        self.host = record.get("ip") or record.get("host")
        port = record.get("agentPort", record.get("port", DEFAULT_PORT))
        if not isinstance(self.host, str) or not self.host or type(port) is not int or not 1 <= port <= 65535:
            raise Failure("the registry record has no usable address")
        self.port = port
        self.__token = record["token"]

    def call(self, method, path, body=None):
        for _ in range(3):
            status, value = self.call_once(method, path, body)
            wait = value.get("retryAfterSec")
            if status != 429 or type(wait) is not int or not 0 < wait <= 30:
                return status, value
            time.sleep(wait)  # the backup routes allow one call per route every few seconds
        return status, value

    def call_once(self, method, path, body):
        payload = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Authorization": "Bearer " + self.__token, "Connection": "close"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        connection = http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT_SEC)
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            data = response.read(MAX_REPLY_BYTES + 1)
            status = response.status
        except (OSError, http.client.HTTPException):
            raise Failure("could not reach the Portal's fleet agent")
        finally:
            connection.close()
        if len(data) > MAX_REPLY_BYTES:
            raise Failure("the Portal's reply is too large")
        try:
            value = loads(data)
        except ValueError:
            value = None
        if not isinstance(value, dict):
            if status == 404:
                raise Failure("this Portal has no sealed backup routes (update it to Hearthpane 1.275 or later)")
            raise Failure("the Portal sent an invalid reply (HTTP %d)" % status)
        return status, value


def refused(status, value):
    code = value.get("error")
    code = code if isinstance(code, str) and ERROR_CODE.fullmatch(code) else "unknown"
    return Failure("the Portal refused (HTTP %d, %s)" % (status, code))


def usb_command(action, serial, extra=()):
    return "adb -s %s shell am broadcast -a %s %s-p <package>" % (serial, action, "".join(e + " " for e in extra))


def confirm_over_usb(serial, mine):
    """Sends the USB confirmation when this Portal is on adb; False when it isn't reachable."""
    adb = os.environ.get("IMMORTAL_BACKUP_ADB") or shutil.which("adb")
    if not adb or not SERIAL.fullmatch(serial):
        return False
    try:
        # Always scoped to this serial (-s); never a device inventory.
        state = subprocess.run([adb, "-s", serial, "get-state"], capture_output=True, text=True, timeout=30)
        if state.returncode != 0 or state.stdout.strip() != "device":
            return False
        # Explicit -s: only this Portal. Both packages: the agent may run in either.
        for package in PACKAGES:
            subprocess.run([adb, "-s", serial, "shell", "am", "broadcast", "-a", CONFIRM_ACTION,
                            "--es", "keyId", mine, "-p", package], capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return False
    return True


def enrol(portal, key):
    spki = public_spki(key)
    mine = key_id(spki)
    for attempt in range(6):
        status, value = portal.call("POST", "/backup/key", {"publicKey": b64(spki)})
        if status == 409 and value.get("error") == "backup_key_mismatch":
            theirs = value.get("keyId")
            theirs = theirs if isinstance(theirs, str) and HEX_ID.fullmatch(theirs) else "unknown"
            raise Failure(
                "this Portal is enrolled with another backup key (%s, this Mac has %s). To replace it, "
                "connect the Portal over USB and run: %s; then rerun fleet-backup.sh"
                % (theirs, mine, usb_command(FORGET_ACTION, portal.serial or "<serial>")))
        if value.get("ok") is not True or value.get("keyId") != mine:
            raise refused(status, value)
        if status == 200 and value.get("result") == "unchanged":
            if attempt:
                say("  confirmed this Mac's backup key %s over USB" % mine)
            return mine
        if status != 202 or value.get("result") != "pending":
            raise refused(status, value)
        if attempt == 0:
            if not confirm_over_usb(portal.serial, mine):
                raise Failure(
                    "this Portal is waiting for USB confirmation of this Mac's backup key %s. Connect "
                    "it over USB (adb) and rerun fleet-backup.sh, or run: %s"
                    % (mine, usb_command(CONFIRM_ACTION, portal.serial or "<serial>", ("--es keyId " + mine,))))
        time.sleep(2)  # the broadcast is delivered asynchronously; the route also wants a short gap
    raise Failure("the Portal did not confirm this Mac's backup key %s over USB" % mine)


# --- envelopes ----------------------------------------------------------------------------

def parse_envelope(value):
    if (not isinstance(value, dict) or value.get("format") != FORMAT or value.get("version") != VERSION
            or set(value) != {"format", "version", "header", "wrappedKey", "nonce", "ciphertext"}):
        raise Failure("the sealed backup is malformed")
    parts = {name: unb64(value[name], limit) for name, limit in (
        ("header", 4096), ("wrappedKey", 1024), ("nonce", 64), ("ciphertext", MAX_SNAPSHOT_BYTES))}
    try:
        header = loads(parts["header"])
    except ValueError:
        raise Failure("the sealed backup header is malformed")
    if (not isinstance(header, dict) or header.get("format") != FORMAT or header.get("version") != VERSION
            or header.get("alg") != ALG or not isinstance(header.get("device"), str)
            or not HEX_ID.fullmatch(header["device"]) or len(header["device"]) != 32):
        raise Failure("the sealed backup header is malformed")
    parts["headerJson"] = header
    return parts


def open_archive(parts, key):
    """The data key and a validated summary of the archive. The plaintext stays in memory."""
    _, _, AESGCM, oaep, InvalidTag = primitives()
    try:
        data_key = key.decrypt(parts["wrappedKey"], oaep)
    except ValueError:
        raise Failure("this Mac's backup key can't open the snapshot (it was sealed to another key)")
    if len(data_key) != 32 or len(parts["nonce"]) != 12:
        raise Failure("the sealed backup is malformed")
    try:
        packed = AESGCM(data_key).decrypt(parts["nonce"], parts["ciphertext"], parts["header"])
    except InvalidTag:
        raise Failure("the sealed backup is damaged or was changed (its seal does not verify)")
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        plain = inflater.decompress(packed, MAX_PLAIN_BYTES + 1)
    except zlib.error:
        raise Failure("the sealed backup is malformed")
    if len(plain) > MAX_PLAIN_BYTES or inflater.unconsumed_tail:
        raise Failure("the sealed backup is too large")
    if not inflater.eof:
        raise Failure("the sealed backup is malformed")
    try:
        archive = loads(plain)
    except ValueError:
        raise Failure("the sealed backup content is malformed")
    del plain
    if (not isinstance(archive, dict) or archive.get("format") != ARCHIVE_FORMAT
            or archive.get("version") != VERSION or archive.get("device") != parts["headerJson"]["device"]
            or not all(isinstance(archive.get(name), dict) for name in ("prefs", "files", "household"))):
        raise Failure("the sealed backup content is malformed")
    summary = {"prefFiles": len(archive["prefs"]),
               "prefValues": sum(len(v) for v in archive["prefs"].values() if isinstance(v, dict)),
               "dataFiles": len(archive["files"]), "householdSettings": len(archive["household"])}
    return data_key, summary


def describe(summary):
    return "%d preference files (%d values), %d data files, %d household settings" % (
        summary["prefFiles"], summary["prefValues"], summary["dataFiles"], summary["householdSettings"])


# --- snapshots ----------------------------------------------------------------------------

def write_snapshot(directory, snapshot):
    path = Path(directory) / SNAPSHOT_NAME
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(json.dumps(snapshot, indent=1) + "\n")


def read_snapshot(directory):
    try:
        descriptor = os.open(Path(directory) / SNAPSHOT_NAME, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        raise Failure("the snapshot has no sealed backup")
    with os.fdopen(descriptor, "rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise Failure("the sealed backup is not a regular file")
        data = stream.read(MAX_SNAPSHOT_BYTES + 1)
    if len(data) > MAX_SNAPSHOT_BYTES:
        raise Failure("the sealed backup is too large")
    try:
        snapshot = loads(data)
    except ValueError:
        raise Failure("the sealed backup is malformed")
    if (not isinstance(snapshot, dict) or snapshot.get("format") != SNAPSHOT_FORMAT
            or snapshot.get("version") != VERSION or not isinstance(snapshot.get("serial"), str)):
        raise Failure("the sealed backup is malformed")
    return snapshot


# --- commands -----------------------------------------------------------------------------

def export(registry_path, directory):
    portal = Portal(registry_path)
    key = load_key(create=True)
    mine = enrol(portal, key)
    status, value = portal.call("POST", "/backup/export", {"keyId": mine})
    if status != 200 or value.get("ok") is not True:
        raise refused(status, value)
    parts = parse_envelope(value.get("backup"))
    header = parts["headerJson"]
    if header.get("keyId") != mine or value.get("device") != header["device"]:
        raise Failure("the Portal's export does not match this Mac's key")
    # Prove this Mac can open it (and that it is intact) before calling the snapshot complete.
    _, summary = open_archive(parts, key)
    write_snapshot(directory, {
        "format": SNAPSHOT_FORMAT, "version": VERSION, "serial": portal.serial, "keyId": mine,
        "device": header["device"], "createdAt": header.get("createdAt"),
        "versionCode": header.get("versionCode"), "summary": summary, "backup": value["backup"],
    })
    say("  sealed export: " + describe(summary))


def inspect(directory):
    snapshot = read_snapshot(directory)
    parts = parse_envelope(snapshot.get("backup"))
    _, summary = open_archive(parts, load_key(create=False))
    say("  sealed backup of %s (key %s): %s" % (
        snapshot["serial"] if SERIAL.fullmatch(snapshot["serial"]) else "unknown",
        snapshot.get("keyId") if isinstance(snapshot.get("keyId"), str) and HEX_ID.fullmatch(snapshot["keyId"]) else "unknown",
        describe(summary)))


def import_backup(registry_path, directory, cross_device, same_serial, dry_run):
    portal = Portal(registry_path)
    snapshot = read_snapshot(directory)
    if cross_device and same_serial:
        raise Failure("--cross-device and --same-serial can't be combined")
    if snapshot["serial"] != portal.serial and not cross_device:
        raise Failure("this snapshot is from another Portal; restoring it here needs --cross-device "
                      "(household settings only)")
    serialization, rsa, _, oaep, _ = primitives()
    parts = parse_envelope(snapshot.get("backup"))
    # Open and check everything locally before the Portal is asked to change anything.
    data_key, summary = open_archive(parts, load_key(create=False))
    status, value = portal.call("POST", "/backup/import", {"op": "begin"})
    if status != 200 or value.get("ok") is not True or not isinstance(value.get("session"), str):
        raise refused(status, value)
    try:
        one_time = serialization.load_der_public_key(unb64(value.get("publicKey"), 4096))
    except ValueError:
        raise Failure("the Portal sent an invalid import key")
    if not isinstance(one_time, rsa.RSAPublicKey) or one_time.key_size < RSA_BITS:
        raise Failure("the Portal sent an invalid import key")
    backup = dict(snapshot["backup"], wrappedKey=b64(one_time.encrypt(data_key, oaep)))
    del data_key
    status, value = portal.call("POST", "/backup/import", {
        "op": "apply", "session": value["session"], "backup": backup,
        "crossDevice": bool(cross_device), "sameSerial": bool(same_serial), "dryRun": bool(dry_run)})
    if status == 409 and value.get("error") == "different_device":
        raise Failure("the Portal's app identity differs from the backup's (a reinstall with another "
                      "signing key changes it). This snapshot has the same serial; if this really is "
                      "the same Portal, rerun with --same-serial, or use --cross-device for household "
                      "settings only")
    if status != 200 or value.get("ok") is not True:
        raise refused(status, value)
    plan = value.get("plan") if isinstance(value.get("plan"), dict) else {}
    counts = {name: plan.get(name) if type(plan.get(name)) is int else 0
              for name in ("prefFiles", "prefValues", "householdSettings")}
    files = plan.get("dataFiles") if isinstance(plan.get("dataFiles"), list) else []
    verb = "would restore" if dry_run else "restored"
    say("  %s %d preference files (%d values), %d data files, %d household settings%s" % (
        verb, counts["prefFiles"], counts["prefValues"], len(files), counts["householdSettings"],
        " (cross-device: device-local and secret items skipped)" if cross_device else ""))
    if not dry_run and value.get("rebootRecommended") is True:
        say("  reboot the Portal to finish (some screens read their settings at start)")
    return summary


def main():
    args = sys.argv[1:]
    try:
        if len(args) == 3 and args[0] == "export":
            export(args[1], args[2])
        elif len(args) == 2 and args[0] == "inspect":
            inspect(args[1])
        elif len(args) >= 3 and args[0] == "import" \
                and set(args[3:]) <= {"--cross-device", "--same-serial", "--dry-run"} \
                and len(set(args[3:])) == len(args[3:]):
            flags = args[3:]
            import_backup(args[1], args[2], "--cross-device" in flags, "--same-serial" in flags, "--dry-run" in flags)
        elif args == ["key-id"]:
            say(key_id(public_spki(load_key(create=False))))
        else:
            raise Failure("usage: fleet-backup-seal.py export|inspect|import|key-id ...")
    except Failure as failure:
        complain("  error: %s" % failure)
        return 1
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as error:
        # Only the type: a message could quote a path, a reply or a key.
        complain("  error: sealed backup failed (%s)" % type(error).__name__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
