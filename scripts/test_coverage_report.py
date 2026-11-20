# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Unit tests for scripts/coverage_report.py (python3 -m unittest discover -s scripts -p 'test_coverage_*.py')."""
import struct
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import coverage_report as cr  # noqa: E402


def class_file(this: str, superclass: str, *extra_utf8: str) -> bytes:
    """A minimal class file: constant pool of utf8 + class entries, then access/this/super."""
    pool = []

    def utf8(s):
        b = s.encode()
        pool.append(b"\x01" + struct.pack(">H", len(b)) + b)
        return len(pool)

    def cls(name):
        idx = utf8(name)
        pool.append(b"\x07" + struct.pack(">H", idx))
        return len(pool)

    this_i = cls(this)
    super_i = cls(superclass)
    for s in extra_utf8:
        utf8(s)
    pool.append(b"\x05" + b"\x00" * 8)  # a long: takes two slots
    header = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 61, len(pool) + 2)
    return header + b"".join(pool) + struct.pack(">HHH", 0x21, this_i, super_i)


class ClassifyTest(unittest.TestCase):
    def test_plain_kotlin_class_is_pure(self):
        data = class_file("com/x/Policy", "java/lang/Object", "(Ljava/lang/String;)Z", "Lkotlin/Metadata;")
        self.assertEqual("pure", cr.classify_class(data))

    def test_allowed_android_references_stay_pure(self):
        data = class_file("com/x/Policy", "java/lang/Object", "Landroidx/annotation/VisibleForTesting;",
                          "android/util/Log", "Landroidx/compose/runtime/internal/StabilityInferred;",
                          "(JJ)Landroidx/compose/ui/geometry/Offset;")
        self.assertEqual("pure", cr.classify_class(data))

    def test_context_reference_is_platform_glue(self):
        data = class_file("com/x/Store", "java/lang/Object", "(Landroid/content/Context;)V")
        self.assertEqual("glue:platform", cr.classify_class(data))

    def test_composer_reference_is_compose_glue(self):
        data = class_file("com/x/ScreenKt", "java/lang/Object", "(Landroidx/compose/runtime/Composer;I)V")
        self.assertEqual("glue:compose", cr.classify_class(data))

    def test_activity_subclass_is_component_glue(self):
        data = class_file("com/x/HomeActivity", "androidx/activity/ComponentActivity")
        self.assertEqual("glue:component", cr.classify_class(data))

    def test_lambda_inherits_glue_of_enclosing_class(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "com/x").mkdir(parents=True)
            (root / "com/x/ScreenKt.class").write_bytes(
                class_file("com/x/ScreenKt", "java/lang/Object", "Landroidx/compose/runtime/Composer;"))
            (root / "com/x/ScreenKt$Row$1.class").write_bytes(class_file("com/x/ScreenKt$Row$1", "java/lang/Object"))
            (root / "com/x/ScreenKt$Rules.class").write_bytes(class_file("com/x/ScreenKt$Rules", "java/lang/Object"))
            kinds = cr.classify_tree(root)
        self.assertEqual("glue:compose", kinds["com/x/ScreenKt$Row$1"])
        self.assertEqual("pure", kinds["com/x/ScreenKt$Rules"])


class AreaTest(unittest.TestCase):
    def test_risk_areas_by_prefix(self):
        self.assertEqual("security", cr.area_of("CallingSignalAuth.kt"))
        self.assertEqual("sync", cr.area_of("ChoreCodec.kt"))
        self.assertEqual("money", cr.area_of("ControlCenterTeslaText.kt"))
        self.assertEqual("control-center", cr.area_of("CcPhonePolicy.kt"))
        self.assertEqual("calling", cr.area_of("CallingMoves.kt"))
        self.assertEqual("reminders", cr.area_of("RoutineSchedule.kt"))
        self.assertEqual("alerts", cr.area_of("AlarmSnooze.kt"))
        self.assertEqual("ota", cr.area_of("OtaState.kt"))
        self.assertEqual("settings", cr.area_of("settings/SettingSpec.kt"))
        self.assertEqual("other", cr.area_of("Aurora.kt"))


class ReportTest(unittest.TestCase):
    XML = """<report name="r"><package name="com/immortal/launcher">
      <class name="com/immortal/launcher/Policy" sourcefilename="Policy.kt">
        <counter type="LINE" missed="1" covered="9"/><counter type="BRANCH" missed="6" covered="4"/></class>
      <class name="com/immortal/launcher/PolicyKt" sourcefilename="Policy.kt">
        <counter type="LINE" missed="5" covered="0"/></class>
      <class name="com/immortal/launcher/BuildConfig" sourcefilename="BuildConfig.java">
        <counter type="LINE" missed="3" covered="0"/></class>
    </package></report>"""

    def rows(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d)
            (src / "com/immortal/launcher").mkdir(parents=True)
            (src / "com/immortal/launcher/Policy.kt").write_text("")
            kinds = {"com/immortal/launcher/Policy": "pure", "com/immortal/launcher/PolicyKt": "glue:platform"}
            return cr.rows_from_report(ET.fromstring(self.XML), kinds, src)

    def test_rows_split_one_file_by_kind_and_skip_generated_sources(self):
        rows = self.rows()
        self.assertEqual({("Policy.kt", "pure"), ("Policy.kt", "glue:platform")}, {(r["file"], r["kind"]) for r in rows})
        summary = cr.summarize(rows)
        self.assertEqual(90.0, summary["pure"]["line_pct"])
        self.assertEqual(40.0, summary["pure"]["branch_pct"])
        self.assertEqual(60.0, summary["overall"]["line_pct"])

    def test_gaps_lists_pure_files_under_target_on_branches(self):
        self.assertEqual(["Policy.kt"], [r["file"] for r in cr.gaps(self.rows())])

    def test_pct_of_nothing_is_full(self):
        self.assertEqual(100.0, cr.pct(0, 0))


if __name__ == "__main__":
    unittest.main()
