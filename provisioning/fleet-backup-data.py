#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Validate local fleet backup data and publish only a completed private snapshot."""

import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import uuid
import xml.etree.ElementTree as ET


PACKAGE = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", re.ASCII)


def unique_object(pairs):
    value = {}
    for key, entry in pairs:
        if key in value:
            raise ValueError("duplicate JSON field")
        value[key] = entry
    return value


def read_json(path):
    return json.loads(Path(path).read_bytes(), object_pairs_hook=unique_object)


def success_json(path):
    value = read_json(path)
    if not isinstance(value, dict) or value.get("ok") is not True:
        raise ValueError("missing literal success")
    return value


def validate_json(kind, path):
    value = success_json(path)
    if kind == "info":
        app = value.get("app")
        if not isinstance(app, dict) or type(app.get("versionCode")) is not int or not isinstance(app.get("versionName"), str):
            raise ValueError("invalid app identity")
    elif kind == "apps":
        apps = value.get("apps")
        if not isinstance(apps, list) or any(
            not isinstance(app, dict) or not isinstance(app.get("packageName"), str)
            or not PACKAGE.fullmatch(app["packageName"])
            or not (
                type(app.get("installed")) is bool
                or (type(app.get("installed")) is int and app["installed"] >= 0)
            ) for app in apps
        ):
            raise ValueError("invalid app list")
    else:
        raise ValueError("invalid JSON kind")
    return value


def private_directory(path):
    try:
        os.mkdir(path, 0o700)
    except FileExistsError:
        pass
    # Open without following a directory symlink; chmod only the owned descriptor.
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        status = os.fstat(descriptor)
        if not stat.S_ISDIR(status.st_mode) or status.st_uid != os.geteuid():
            raise ValueError("backup directory is not owned")
        os.fchmod(descriptor, 0o700)
    finally:
        os.close(descriptor)


def is_installed(app):
    marker = app["installed"]
    return marker is True or (type(marker) is int and marker >= 0)


def restore_target(directory, target, snapshot=None):
    fold = str.maketrans("ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")
    records = []
    for path in Path(directory).glob("*.json"):
        if path.suffix != ".json":
            continue
        # Fail closed on unreadable/ambiguous registry data: the CLI's parser can
        # differ, so silently omitting a record could hide an additional target.
        record = read_json(path)
        if not isinstance(record, dict) or not isinstance(record.get("token"), str) or not record["token"]:
            continue
        records.append({
            "serial": record.get("serial") if isinstance(record.get("serial"), str) else "",
            "name": record.get("name") if isinstance(record.get("name"), str) else "",
            "data": record,
        })

    def matches(selector):
        return [
            record for record in records
            if record["serial"] == selector
            or record["name"].translate(fold) == selector.translate(fold)
        ]

    if target.translate(fold) == "all":
        raise ValueError("restore requires one device")
    chosen = matches(target)
    if len(chosen) != 1:
        raise ValueError("restore target is not unique")
    serial = chosen[0]["serial"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", serial) or serial.translate(fold) == "all":
        raise ValueError("unsafe restore serial")
    # The CLI matches both serials and friendly names. A canonical serial can
    # still collide with another room's name, so check the final selector too.
    if len(matches(serial)) != 1:
        raise ValueError("restore selector would fan out")
    if snapshot is not None:
        private_directory(snapshot)
        destination = Path(snapshot) / (serial + ".json")
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(chosen[0]["data"]) + "\n")
    return serial


def restore_packages(path):
    apps = validate_json("apps", path)["apps"]
    return sorted({
        app["packageName"] for app in apps if is_installed(app)
        and app["packageName"] not in {"com.immortal.launcher", "com.immortal.launcher.debug"}
    })


def validate_install(path, package):
    value = success_json(path)
    if value.get("packageName") != package or value.get("result") != "installed":
        raise ValueError("installation is not confirmed")


def validate_presence(path, package):
    apps = validate_json("apps", path)["apps"]
    matches = [app for app in apps if app["packageName"] == package]
    if not matches or not all(is_installed(app) for app in matches):
        raise ValueError("installed package is not present")


def preference_names(path, expected):
    value = success_json(path)
    if value.get("path") != expected or not isinstance(value.get("entries"), list):
        raise ValueError("invalid preference listing")
    names = []
    for entry in value["entries"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("name"), str):
            raise ValueError("invalid preference entry")
        name = entry["name"]
        if entry.get("type") != "file" or not name.endswith(".xml"):
            continue
        if entry.get("readable") is not True or "/" in name or "\\" in name or any(ord(c) < 32 or ord(c) == 127 for c in name):
            raise ValueError("unreadable or unsafe preference entry")
        if name in names:
            raise ValueError("duplicate preference entry")
        names.append(name)
    if not names:
        raise ValueError("no preferences captured")
    return sorted(names)


def validate_xml(path):
    if ET.parse(path).getroot().tag != "map":
        raise ValueError("not a preference map")


def manifest(stage, serial, stamp, package):
    info = validate_json("info", stage / "info.json")
    apps = validate_json("apps", stage / "apps.json")["apps"]
    files = sorted((stage / "shared_prefs").glob("*.xml"))
    if not files:
        raise ValueError("no preferences captured")
    for path in files:
        validate_xml(path)
    registry = read_json(stage / "registry.json")
    if not isinstance(registry, dict) or registry.get("serial") != serial:
        raise ValueError("invalid registry copy")
    installed = sorted({app["packageName"] for app in apps if is_installed(app)})
    lines = [
        "device:  " + serial, "stamp:   " + stamp, "package: " + package,
        "version: " + info["app"]["versionName"],
        "versionCode: " + str(info["app"]["versionCode"]),
        "model: " + str(info.get("model", "")),
        "installed apps:", *("  " + name for name in installed),
        "shared_prefs:", *("  " + path.name for path in files),
    ]
    (stage / "MANIFEST.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def publish(stage, stamp):
    parent = stage.parent
    latest = parent / "latest"
    if os.path.lexists(latest) and not latest.is_symlink():
        raise ValueError("latest is not a snapshot link")
    final = parent / (stamp + "-" + uuid.uuid4().hex)
    link = parent / (".latest-" + uuid.uuid4().hex)
    moved = False
    try:
        os.rename(stage, final)
        moved = True
        os.symlink(final.name, link)
        os.replace(link, latest)
    except Exception:
        if moved:
            shutil.rmtree(final)
        raise
    finally:
        if os.path.lexists(link):
            link.unlink()
    # Publication is the commit point. Output failure must not reverse its verdict.


def main():
    try:
        mode, *args = sys.argv[1:]
        if mode == "package" and len(args) == 1:
            if not PACKAGE.fullmatch(args[0]):
                raise ValueError("invalid package")
        elif mode == "json" and len(args) == 2:
            validate_json(*args)
        elif mode == "directory" and len(args) == 1:
            private_directory(args[0])
        elif mode == "restore-packages" and len(args) == 1:
            print("\n".join(restore_packages(args[0])))
        elif mode == "restore-target" and len(args) in (2, 3):
            print(restore_target(*args))
        elif mode == "install-result" and len(args) == 2:
            validate_install(*args)
        elif mode == "installed-presence" and len(args) == 2:
            validate_presence(*args)
        elif mode == "names" and len(args) == 2:
            print("\n".join(preference_names(*args)))
        elif mode == "xml" and len(args) == 1:
            validate_xml(args[0])
        elif mode == "manifest" and len(args) == 4:
            manifest(Path(args[0]), *args[1:])
        elif mode == "publish" and len(args) == 2:
            publish(Path(args[0]), args[1])
        else:
            raise ValueError("invalid command")
    except (OSError, ValueError, TypeError, ET.ParseError):
        # Never echo raw JSON/XML, paths or exception messages containing credentials.
        print("error: snapshot data is incomplete or invalid", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
