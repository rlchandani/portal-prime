# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Actual readiness CLI with ZIP assets; only external tools/builds are fixtures."""
import hashlib
import json
import unittest

from test_portal_release_readiness import ReadinessFixture


class ReleaseAssetsTest(unittest.TestCase):
    def fixture(self, entries):
        f = ReadinessFixture()
        self.addCleanup(f.close)
        f.write("app/src/main/assets/slideshow/scene.js", "current scene\n")
        binary = f.repo / "app/src/main/assets/fonts/paper.bin"
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b"\x00\xff\x10")
        f.write("app/src/main/assets/empty.txt", "")
        # Preserve the existing SDK fixture's JSON identity contract inside the ZIP.
        for tool in ("aapt", "apksigner"):
            path = f.sdk / "build-tools/36.0.0" / tool
            path.write_text(path.read_text().replace(
                "d=json.loads(pathlib.Path(sys.argv[-1]).read_text())",
                "import zipfile\n"
                "p=pathlib.Path(sys.argv[-1])\n"
                "d=json.loads(zipfile.ZipFile(p).read('metadata.json')) "
                "if zipfile.is_zipfile(p) else json.loads(p.read_text())",
            ))
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + f"""
import warnings, zipfile
with warnings.catch_warnings():
 warnings.simplefilter('ignore', UserWarning)
 with zipfile.ZipFile(p/f'app-{{variant}}.apk','w',compression=zipfile.ZIP_DEFLATED) as z:
  z.writestr('metadata.json',(r/'candidate.json').read_bytes())
  for name, data in {entries!r}:
   z.writestr(name,data)
""")
        return f

    @staticmethod
    def correct_entries():
        return [
            ("assets/slideshow/scene.js", b"current scene\n"),
            ("assets/fonts/paper.bin", b"\x00\xff\x10"),
            ("assets/empty.txt", b""),
        ]

    def assert_rejected(self, entries):
        f = self.fixture(entries)
        result = f.run()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = f.receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertRegex(receipt["error"].lower(), r"asset")
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertFalse((f.output / "candidate.apk").exists())
        self.assertFalse((f.output / ".candidate.pending.apk").exists())

    def test_missing_asset_blocks_candidate_promotion(self):
        self.assert_rejected(self.correct_entries()[1:])

    def test_stale_same_size_asset_blocks_candidate_promotion(self):
        entries = self.correct_entries()
        entries[0] = (entries[0][0], b"outdated data\n")
        self.assertEqual(len(b"current scene\n"), len(entries[0][1]))
        self.assert_rejected(entries)

    def test_truncated_binary_asset_blocks_candidate_promotion(self):
        entries = self.correct_entries()
        entries[1] = (entries[1][0], b"\x00\xff")
        self.assert_rejected(entries)

    def test_duplicate_matching_asset_blocks_ambiguous_lookup(self):
        entries = self.correct_entries()
        self.assert_rejected(entries + [entries[0]])

    def test_nested_compressed_binary_and_empty_assets_record_exact_evidence(self):
        f = self.fixture(self.correct_entries())
        result = f.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = f.receipt()
        self.assertEqual("ready", receipt["status"])
        evidence = receipt.get("sourceAssets")
        self.assertIsNotNone(evidence, "Ready receipt must bind its packaged source assets")
        self.assertEqual(3, evidence["fileCount"])
        self.assertEqual(
            [{"path": name.removeprefix("assets/"), "bytes": len(data),
              "sha256": hashlib.sha256(data).hexdigest()}
             for name, data in sorted(self.correct_entries())],
            evidence["files"],
        )
        self.assertTrue((f.output / "candidate.apk").is_file())

    def test_unreadable_asset_crc_writes_failed_receipt_and_retires_pending_apk(self):
        f = self.fixture(self.correct_entries())
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + """
import struct
apk=p/f'app-{variant}.apk'
data=bytearray(apk.read_bytes())
offset=data.find(b'PK\\x01\\x02')
while offset >= 0:
 size,extra,comment=struct.unpack_from('<HHH',data,offset+28)
 name=data[offset+46:offset+46+size]
 if name == b'assets/slideshow/scene.js':
  crc=struct.unpack_from('<I',data,offset+16)[0]
  struct.pack_into('<I',data,offset+16,crc ^ 1)
  break
 offset=data.find(b'PK\\x01\\x02',offset+46+size+extra+comment)
else:
 raise AssertionError('Fixture source asset central entry not found')
apk.write_bytes(data)
""")
        result = f.run()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("asset", f.receipt()["error"].lower())
        self.assertFalse((f.output / "candidate.apk").exists())
        self.assertFalse((f.output / ".candidate.pending.apk").exists())

    def test_invalid_compressed_asset_writes_failed_receipt(self):
        f = self.fixture(self.correct_entries())
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + """
import struct
apk=p/f'app-{variant}.apk'
with zipfile.ZipFile(apk) as z:
 offset=z.getinfo('assets/slideshow/scene.js').header_offset
data=bytearray(apk.read_bytes())
name,extra=struct.unpack_from('<HH',data,offset+26)
data[offset+30+name+extra]=7  # Invalid DEFLATE block type, preserving identity entry.
apk.write_bytes(data)
""")
        result = f.run()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("asset", f.receipt()["error"].lower())
        self.assertFalse((f.output / "candidate.apk").exists())
        self.assertFalse((f.output / ".candidate.pending.apk").exists())


if __name__ == "__main__":
    unittest.main()
