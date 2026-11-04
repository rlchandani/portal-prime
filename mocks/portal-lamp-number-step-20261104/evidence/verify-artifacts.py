from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = Path('/private/tmp/portal-next-20261104-135432')
r = Path('~/.codex/worktrees/portal-music-player/portal-prime')
tools = Path('/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0')
assert json.loads((p/'build-terminal.json').read_text())['exitCode'] == 0
spec = importlib.util.spec_from_file_location('readiness', r/'scripts/portal_release_readiness.py')
readiness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readiness)
result = {}
for variant in ('debug', 'performance'):
    src = r/f'app/build/outputs/apk/{variant}/app-{variant}.apk'
    dst = p/f'candidate-{variant}-1.162.apk'
    if dst.exists():
        assert dst.read_bytes() == src.read_bytes()
    else:
        shutil.copyfile(src, dst)
        dst.chmod(0o400)
    manifest = subprocess.check_output(
        [str(tools/'aapt'), 'dump', 'badging', str(dst)], text=True).splitlines()[0]
    assert "name='com.immortal.launcher.debug'" in manifest
    assert "versionCode='156'" in manifest and "versionName='1.162'" in manifest
    cert = subprocess.check_output(
        [str(tools/'apksigner'), 'verify', '--print-certs', str(dst)], text=True)
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', cert).group(1)
    assert signer == '4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6'
    source_assets = readiness.source_assets(r, dst)
    assert source_assets['fileCount'] == 23
    baseline = p/('before-debug-1.161.apk' if variant == 'debug' else 'rollback-1.161.apk')
    with zipfile.ZipFile(dst) as z, zipfile.ZipFile(baseline) as b:
        names = sorted(n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/'))
        before_names = sorted(n for n in b.namelist() if n.startswith('assets/') and not n.endswith('/'))
        assert names == before_names
        changed = [n for n in names if z.read(n) != b.read(n)]
        assert changed == ([] if variant == 'debug' else ['assets/dexopt/baseline.prof'])
        assert all(z.read('assets/'+item['path']) == b.read('assets/'+item['path'])
                   for item in source_assets['files'])
    result[variant] = {
        'path': str(dst), 'bytes': dst.stat().st_size,
        'sha256': hashlib.sha256(dst.read_bytes()).hexdigest(),
        'manifest': manifest, 'signerSha256': signer, 'sourceAssets': source_assets,
        'allSourceAssetsUnchangedFromBaseline': True,
        'generatedOptimizationProfileChanges': changed,
    }
count = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
for xml in (r/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root = ET.parse(xml).getroot()
    count['suites'] += 1
    for key in ('tests', 'failures', 'errors', 'skipped'):
        count[key] += int(root.get(key, 0))
assert count['tests'] == 4525 and count['suites'] == 472
assert count['failures'] == count['errors'] == count['skipped'] == 0
result['jvm'] = count
result['gradleExit'] = 0
(p/'promotion-artifacts.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps({
    'artifacts': {v: {k: result[v][k] for k in ('sha256', 'bytes', 'signerSha256')}
                  for v in ('debug', 'performance')}, 'jvm': count,
}))
