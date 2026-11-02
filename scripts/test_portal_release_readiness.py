# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Exercise the actual readiness CLI; only external build/SDK processes are fixtures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("portal_release_readiness.py")
GIT = shutil.which("git")


class ReadinessFixture:
    def __init__(self):
        self.temp = tempfile.TemporaryDirectory(prefix="portal-readiness-test-")
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.sdk = self.root / "sdk"
        bt = self.sdk / "build-tools/36.0.0"
        bt.mkdir(parents=True)
        self.output = self.root / "receipt"
        self.baseline = self.root / "accepted.apk"
        self.metadata = {"package": "com.immortal.launcher.debug", "code": 62,
                         "name": "1.68", "signer": "a" * 64}
        self.baseline.write_text(json.dumps(dict(self.metadata, code=61, name="1.67")))
        self.write("app/build.gradle.kts", 'versionCode = 62\nversionName = "1.68"\n')
        self.write("version.json", json.dumps({"versionCode": 62, "versionName": "1.68",
                   "apkUrl": "https://github.com/starbrightlab/immortal/releases/latest/download/immortal.apk",
                   "notes": "Fixture"}))
        self.write("candidate.json", json.dumps(self.metadata))
        self.write("app/src/test/js/example.test.mjs", "// fixture\n")
        for name in ("test_slideshow_sources.mjs", "test_slideshow_labels.mjs",
                     "tests/slideshow_native_filmstrip.test.cjs"):
            self.write("scripts/" + name, "// fixture\n")
        self.write("scripts/test_portal_fixture.py",
                   "import unittest\nclass Fixture(unittest.TestCase):\n"
                   " def test_gate(self): self.assertEqual(2, 1 + 1)\n")
        self.write("scripts/check-version-sync.sh", "#!/bin/bash\nexit 0\n")
        self.executable(self.repo / "gradlew", """
import json, pathlib, sys, time
r=pathlib.Path.cwd()
with open(r/'commands.log','a') as f:f.write(json.dumps(['gradlew']+sys.argv[1:])+'\\n')
if (r/'sleep-gradle').exists():time.sleep(30)
if (r/'fail-gradle').exists():sys.exit(7)
p=r/'app/build/test-results/testDebugUnitTest';p.mkdir(parents=True,exist_ok=True)
if not (r/'missing-tests').exists():
 (p/'TEST-fixture.xml').write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
variant=next(a.removeprefix(':app:assemble').lower() for a in sys.argv if a.startswith(':app:assemble'))
p=r/f'app/build/outputs/apk/{variant}';p.mkdir(parents=True,exist_ok=True)
(p/f'app-{variant}.apk').write_bytes((r/'candidate.json').read_bytes())
""")
        self.executable(self.tools / "node", """
import json,pathlib,sys
with open(pathlib.Path.cwd()/'commands.log','a') as f:f.write(json.dumps(['node']+sys.argv[1:])+'\\n')
print('v24.0.0' if '--version' in sys.argv else '# tests 1\\n# fail 0')
""")
        self.executable(bt / "aapt", """
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[-1]).read_text())
print(f"package: name='{d['package']}' versionCode='{d['code']}' versionName='{d['name']}'")
""")
        self.executable(bt / "apksigner", """
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[-1]).read_text())
if not d.get('signer'):sys.exit(2)
print('Signer #1 certificate SHA-256 digest: '+d['signer'])
""")
        for command in ("gh", "curl", "wget", "adb"):
            self.executable(self.tools / command, "raise SystemExit('forbidden command')\n")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ["PATH"])

    def write(self, relative, text):
        p = self.repo / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return p

    @staticmethod
    def executable(path, body):
        path.write_text("#!" + sys.executable + "\n" + body)
        path.chmod(0o755)

    def git(self, *args):
        return subprocess.run([GIT, "-c", "core.hooksPath=/dev/null",
                               "--no-optional-locks", *args], cwd=self.repo,
                              check=True, capture_output=True, text=True)

    def run(self, *extra):
        return subprocess.run([sys.executable, str(SCRIPT), "--repo-root", str(self.repo),
                               "--baseline-apk", str(self.baseline), "--sdk", str(self.sdk),
                               "--variant", "performance", "--output", str(self.output),
                               *extra], env=self.env, capture_output=True, text=True, timeout=45)

    def receipt(self):
        return json.loads((self.output / "receipt.json").read_text())

    def close(self):
        self.temp.cleanup()


class ReleaseReadinessTest(unittest.TestCase):
    def setUp(self):
        self.fixture = ReadinessFixture()
        self.addCleanup(self.fixture.close)

    def test_success_records_real_candidate_identity_without_publishing(self):
        f = self.fixture
        before = (f.repo / "version.json").read_bytes()
        result = f.run()
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        r = f.receipt()
        self.assertEqual("ready", r["status"])
        self.assertFalse(r["published"])
        self.assertFalse(r["deployed"])
        self.assertEqual(62, r["artifact"]["versionCode"])
        self.assertEqual("com.immortal.launcher.debug", r["artifact"]["package"])
        self.assertEqual(3, r["jvm"]["tests"])
        self.assertEqual(before, (f.repo / "version.json").read_bytes())
        self.assertEqual((f.repo / "candidate.json").read_bytes(),
                         (f.output / "candidate.apk").read_bytes())
        calls = [json.loads(x) for x in (f.repo / "commands.log").read_text().splitlines()]
        gradle = next(c for c in calls if c[0] == "gradlew")
        self.assertIn("--offline", gradle)
        self.assertIn(":app:testDebugUnitTest", gradle)
        self.assertIn(":app:assemblePerformance", gradle)

    def test_stale_apk_is_rejected_even_when_build_exits_successfully(self):
        f = self.fixture
        f.metadata["code"] = 61
        f.write("candidate.json", json.dumps(f.metadata))
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertNotIn("candidate.apk", [p.name for p in f.output.iterdir()])

    def test_different_baseline_signer_is_never_silently_skipped(self):
        f = self.fixture
        f.baseline.write_text(json.dumps(dict(f.metadata, signer="b" * 64, code=61)))
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("sign", f.receipt()["error"].lower())

    def test_report_directory_cannot_mutate_repository_sources(self):
        f = self.fixture
        target = f.repo / "scripts/reports"
        result = f.run("--output", str(target))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(target.exists())

    def test_stale_jvm_reports_do_not_count_as_current_checks(self):
        f = self.fixture
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + """
import os
os.utime(r/'app/build/test-results/testDebugUnitTest/TEST-fixture.xml',(1,1))
""")
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("report", f.receipt()["error"].lower())

    def test_packaged_asset_directory_named_build_is_still_a_source_input(self):
        f = self.fixture
        f.write("app/src/main/assets/build/index.js", "original")
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + """
(r/'app/src/main/assets/build/index.js').write_text('changed during build')
""")
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("changed", f.receipt()["error"].lower())

    def test_unchanged_recent_prior_jvm_report_cannot_report_ready(self):
        f = self.fixture
        data = '<testsuite tests="3" failures="0" errors="0" skipped="0"/>'
        old = f.write("app/build/test-results/testDebugUnitTest/TEST-prior.xml", data)
        f.write("missing-tests", "")
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertFalse(old.exists())
        self.assertEqual(data, (f.output / "prior-jvm-reports/TEST-prior.xml").read_text())

    def test_explicit_sdk_is_passed_to_the_android_build(self):
        f = self.fixture
        f.env.pop("ANDROID_HOME", None)
        f.env.pop("ANDROID_SDK_ROOT", None)
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + f"""
import os
if str(pathlib.Path(os.environ.get('ANDROID_HOME','/missing')).resolve()) != {str(f.sdk.resolve())!r}:sys.exit(9)
if str(pathlib.Path(os.environ.get('ANDROID_SDK_ROOT','/missing')).resolve()) != {str(f.sdk.resolve())!r}:sys.exit(9)
""")
        result = f.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("ready", f.receipt()["status"])

    def test_node_gates_request_tap_independently_of_the_default_reporter(self):
        f = self.fixture
        f.executable(f.tools / "node", """
import sys
args=sys.argv[1:]
if '--version' in args:print('v24.0.0')
elif '--test-reporter=tap' in args or any(args[i:i+2]==['--test-reporter','tap'] for i in range(len(args))):
 print('# tests 1\\n# fail 0')
else:print('ℹ tests 1\\nℹ fail 0')
""")
        result = f.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(1, f.receipt()["javascript"]["remote-js"])


if __name__ == "__main__":
    unittest.main()
