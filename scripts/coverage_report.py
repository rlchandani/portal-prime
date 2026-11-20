#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Summarize JVM unit-test coverage, split into pure logic and Android/Compose glue.

Run the opt-in coverage build first:

    ./gradlew :app:createDebugUnitTestCoverageReport -PjacocoReport=true

then, from the repository root:

    python3 scripts/coverage_report.py              # overall, per package, per risk area
    python3 scripts/coverage_report.py --gaps       # + pure-logic files under 80%, ranked by risk
    python3 scripts/coverage_report.py --json out.json

Classification is per compiled class, from its constant pool (so a pure policy object living in a
file next to an Activity is still counted as logic):

- glue:component  subclasses an Activity, Service, Receiver, View, ...
- glue:compose    references the Compose Composer (composables, their lambdas, ComposableSingletons)
- glue:platform   references any other android/androidx type (Context, SharedPreferences, Bitmap,
                  Handler, MediaCodec, Compose snapshot state, ...)
- pure            none of the above. Annotations, android.util.Log, SystemClock and Compose's
                  pure-Kotlin value types (geometry, unit, Color) are allowed.

Lambdas and anonymous classes inherit the kind of their enclosing class when that class is glue.
Risk areas come from file-name prefixes (see AREAS).
"""
import argparse
import json
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SRC = Path("app/src/main/java")
CLASSES = Path("app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
REPORT = Path("app/build/reports/coverage/test/debug/report.xml")
PACKAGE_ROOT = "com/immortal/launcher"
TARGET_PCT = 80.0

ALLOWED_ANDROID = (
    "androidx/annotation/", "android/annotation/", "android/util/Log", "android/os/SystemClock",
    # Compose-compiler stability annotations land on every class; they carry no behaviour.
    "androidx/compose/runtime/internal/StabilityInferred", "androidx/compose/runtime/Immutable",
    "androidx/compose/runtime/Stable",
    # Pure-Kotlin value types (inline classes, plain math) that run unchanged on the JVM.
    "androidx/compose/ui/geometry/", "androidx/compose/ui/unit/", "androidx/compose/ui/util/",
    "androidx/compose/ui/graphics/Color", "androidx/compose/ui/graphics/ColorKt",
)
COMPONENT_SUPERS = {
    "android/app/Activity", "androidx/activity/ComponentActivity", "android/app/Service",
    "android/service/dreams/DreamService", "android/content/BroadcastReceiver",
    "android/accessibilityservice/AccessibilityService",
    "android/service/notification/NotificationListenerService", "android/app/admin/DeviceAdminReceiver",
    "android/app/Application", "android/view/View", "android/view/SurfaceView", "android/view/TextureView",
    "android/widget/FrameLayout", "android/view/ViewGroup", "android/webkit/WebView",
    "android/content/ContentProvider", "android/opengl/GLSurfaceView",
}
ANDROID_REF = re.compile(r"(?:^|[L\[;(])(androidx?/[\w/$]+)")
ANONYMOUS = re.compile(r"\$(\d+|.*lambda.*|.*inlined.*)$")

# Ordered by risk; the first match wins.
AREAS = [
    ("security", r"Auth|Crypto(?!Feed|Config|Settings)|Cipher|Keys\b|KeyStore|Pairing|Seal|Secret|"
                 r"Handshake|PinPad|PinTries|FetchUrlPolicy|PeerRequestGate|HouseholdKey|BoundedRead|"
                 r"FleetFileRead|FleetFs|FleetUploadWriter|FleetRoutes|FleetHttpServer|RemoteSessionPolicy|"
                 r"GuestPasswordStore|DevMode|RemotePairedPhones|OtaIntegrity|FleetBackupLimiter|"
                 r"CcRemoteLimiter|CcRemoteConfirm|CallMessageKeys|YtOAuth"),
    ("sync", r"Sync|Merge|Codec|Wire|Backup|Restore|HouseholdBus|HouseholdMessage|^SharedTimer|^Chore|"
             r"^HouseholdList|RoomSeen|Handoff"),
    ("money", r"^Alpaca|CurrencyRate|^CryptoFeed|^Stocks|ControlCenterTesla|^CcRemoteTesla"),
    ("control-center", r"^ControlCenter|^Cc"),
    ("calling", r"^Call|^Calling|^Intercom|^LanAudio|^LanPcm|^Dinner"),
    ("reminders", r"^Reminder|^Routine|ToolsReminders"),
    ("alerts", r"Alert|Alarm|^Doorbell|^Timer|^Chime|^Sunrise|Notification|^Sleep"),
    ("ota", r"^Ota|Update|Install|^Apk|SelfInstall|PackageInstall|^StoreCatalog|IncomingApk"),
    ("settings", r"Settings|Config\b"),
]
AREA_ORDER = [a for a, _ in AREAS] + ["other"]


def constant_pool(data: bytes):
    """Returns (utf8 strings, superclass internal name) from a class file."""
    count = struct.unpack(">H", data[8:10])[0]
    pos, i = 10, 1
    utf8, class_refs = {}, {}
    while i < count:
        tag = data[pos]
        if tag == 1:
            ln = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            utf8[i] = data[pos + 3:pos + 3 + ln].decode("utf-8", "replace")
            pos += 3 + ln
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1  # 8-byte constants take two slots
        elif tag == 7:
            class_refs[i] = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            pos += 3
        elif tag in (8, 16, 19, 20):
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(f"unknown constant-pool tag {tag}")
        i += 1
    super_index = struct.unpack(">H", data[pos + 4:pos + 6])[0]
    return list(utf8.values()), utf8.get(class_refs.get(super_index, 0), "")


def classify_class(data: bytes) -> str:
    strings, superclass = constant_pool(data)
    if superclass in COMPONENT_SUPERS:
        return "glue:component"
    if any("androidx/compose/runtime/Composer" in s for s in strings):
        return "glue:compose"
    for s in strings:
        for m in ANDROID_REF.finditer(s):
            if not m.group(1).startswith(ALLOWED_ANDROID):
                return "glue:platform"
    return "pure"


def classify_tree(classes_dir: Path) -> dict:
    kinds = {str(p.relative_to(classes_dir))[:-6]: classify_class(p.read_bytes())
             for p in classes_dir.rglob("*.class")}
    for name, kind in list(kinds.items()):
        if "ComposableSingletons" in name:
            kinds[name] = "glue:compose"
        elif kind == "pure" and "$" in name and ANONYMOUS.search(name):
            outer = kinds.get(name.split("$", 1)[0], "pure")
            if outer != "pure":
                kinds[name] = outer
    return kinds


def area_of(rel: str) -> str:
    name = rel.split("/")[-1].removesuffix(".kt")
    for area, rx in AREAS:
        if re.search(rx, name):
            return area
    if rel.startswith("settings/"):
        return "settings"
    return "other"


def pct(missed: int, covered: int) -> float:
    return 100.0 * covered / (missed + covered) if missed + covered else 100.0


def rows_from_report(report_root, kinds: dict, src_root: Path) -> list:
    """One row per (source file, kind) with summed line/branch counters of its classes."""
    rows = []
    for pkg in report_root.findall("package"):
        pname = pkg.get("name")
        per = {}
        for cl in pkg.findall("class"):
            fname = cl.get("sourcefilename") or ""
            if not fname or not (src_root / pname / fname).exists():
                continue  # generated sources (BuildConfig, R, ...)
            kind = kinds.get(cl.get("name"), "glue:platform")
            acc = per.setdefault((fname, kind), [0, 0, 0, 0])
            for c in cl.findall("counter"):
                missed, covered = int(c.get("missed")), int(c.get("covered"))
                if c.get("type") == "LINE":
                    acc[0] += missed; acc[1] += covered
                elif c.get("type") == "BRANCH":
                    acc[2] += missed; acc[3] += covered
        for (fname, kind), (lm, lc, bm, bc) in sorted(per.items()):
            rel = f"{pname.removeprefix(PACKAGE_ROOT).lstrip('/')}/{fname}".lstrip("/")
            rows.append(dict(file=rel, package=pname.replace("/", "."), kind=kind, area=area_of(rel),
                             lm=lm, lc=lc, bm=bm, bc=bc))
    return rows


def aggregate(rows: list) -> dict:
    lm = sum(r["lm"] for r in rows); lc = sum(r["lc"] for r in rows)
    bm = sum(r["bm"] for r in rows); bc = sum(r["bc"] for r in rows)
    return dict(files=len({r["file"] for r in rows}), lines=lm + lc, line_pct=round(pct(lm, lc), 1),
                branches=bm + bc, branch_pct=round(pct(bm, bc), 1), missed_lines=lm, missed_branches=bm)


def summarize(rows: list) -> dict:
    pure = [r for r in rows if r["kind"] == "pure"]
    return {
        "overall": aggregate(rows),
        "pure": aggregate(pure),
        "glue": aggregate([r for r in rows if r["kind"] != "pure"]),
        "by_kind": {k: aggregate([r for r in rows if r["kind"] == k]) for k in sorted({r["kind"] for r in rows})},
        "by_package": {p: aggregate([r for r in rows if r["package"] == p]) for p in sorted({r["package"] for r in rows})},
        "pure_by_package": {p: aggregate([r for r in pure if r["package"] == p]) for p in sorted({r["package"] for r in pure})},
        "pure_by_area": {a: aggregate([r for r in pure if r["area"] == a]) for a in AREA_ORDER if any(r["area"] == a for r in pure)},
        "all_by_area": {a: aggregate([r for r in rows if r["area"] == a]) for a in AREA_ORDER if any(r["area"] == a for r in rows)},
    }


def gaps(rows: list, min_missed: int = 3) -> list:
    """Pure-logic files under the target on lines or branches, by risk area then missed lines+branches."""
    out = [r for r in rows if r["kind"] == "pure"
           and (pct(r["lm"], r["lc"]) < TARGET_PCT or pct(r["bm"], r["bc"]) < TARGET_PCT)
           and (r["lm"] >= min_missed or r["bm"] >= min_missed + 1)]
    return sorted(out, key=lambda r: (AREA_ORDER.index(r["area"]), -(r["lm"] + r["bm"]), r["file"]))


def fmt(label: str, a: dict) -> str:
    return (f"{label:30} files={a['files']:4} lines={a['lines']:6} line%={a['line_pct']:5.1f} "
            f"branches={a['branches']:6} branch%={a['branch_pct']:5.1f}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=Path("."))
    ap.add_argument("--report", type=Path)
    ap.add_argument("--classes", type=Path)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--gaps", action="store_true")
    args = ap.parse_args(argv)
    report = args.report or args.root / REPORT
    classes = args.classes or args.root / CLASSES
    if not report.exists() or not classes.exists():
        print(f"missing {report if not report.exists() else classes}; run "
              "./gradlew :app:createDebugUnitTestCoverageReport -PjacocoReport=true first", file=sys.stderr)
        return 2
    rows = rows_from_report(ET.parse(report).getroot(), classify_tree(classes), args.root / SRC)
    summary = summarize(rows)
    if args.json:
        args.json.write_text(json.dumps(dict(summary=summary, files=rows), indent=1))
    print(fmt("overall", summary["overall"]))
    print(fmt("pure logic", summary["pure"]))
    print(fmt("glue (all)", summary["glue"]))
    for k, a in summary["by_kind"].items():
        if k != "pure":
            print(fmt(f"  {k}", a))
    for title, key in (("per package (all)", "by_package"), ("per package (pure logic)", "pure_by_package"),
                       ("pure logic by risk area", "pure_by_area"), ("all code by risk area", "all_by_area")):
        print(f"-- {title}")
        for name, a in summary[key].items():
            print(fmt(name.replace("com.immortal.launcher", "launcher"), a))
    if args.gaps:
        print(f"-- pure-logic files under {TARGET_PCT:.0f}% (risk order, then missed lines+branches)")
        for r in gaps(rows):
            print(f"{r['area']:14} {r['file']:46} L {pct(r['lm'], r['lc']):5.1f}% ({r['lm']}/{r['lm'] + r['lc']})"
                  f"  B {pct(r['bm'], r['bc']):5.1f}% ({r['bm']}/{r['bm'] + r['bc']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
