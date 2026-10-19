#!/usr/bin/env python3
"""Android API surface check; project/AndroidX symbols remain stubs."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
generated = root / "host-tests/generated"
support = [str(p) for p in generated.rglob("*.java")
           if p.relative_to(generated).parts[0] != "android"]
out = root / "api35-classes"
out.mkdir(exist_ok=True)
base = root / "patched/Snapcast/src/main/java/de/badaix/snapcast"
result = subprocess.run([
    "javac", "--release", "17", "-classpath", str(root / "android-api35.jar"),
    "-d", str(out), *support, str(base / "SnapclientService.java"),
    str(base / "FocusControlService.java"), str(base / "BroadcastReceiver.java")
], capture_output=True, text=True)
text = f"Official API 35 javac check: {result.returncode}\n{result.stdout}{result.stderr}"
print(text)
(root / "api35-check.txt").write_text(text)
sys.exit(result.returncode)
