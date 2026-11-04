from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile

p = Path('/private/tmp/portal-next-20261104-135432')
phase = sys.argv[1]
assert phase in ('before', 'after')
package = 'com.immortal.lampstepfixture'
fixture = p/'native-owned/lamp-step-fixture.apk'
prep = json.loads((p/'native-owned/preparation-verification.json').read_text())
source = p/('before-debug-1.161.apk' if phase == 'before' else 'candidate-debug-1.162.apk')
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()

def adb(*args, check=True, timeout=45):
    return subprocess.run(['adb', '-s', '192.168.0.70:5555', *args],
                          capture_output=True, check=check, timeout=timeout)

assert adb('shell', 'getprop', 'ro.serialno').stdout.decode().strip() == 'FAMILY_PORTAL_SERIAL'
assert not adb('shell', 'pm', 'path', package, check=False).stdout.strip()
for name, binding in prep['sources'].items():
    f = p/'native-owned'/name
    assert sha(f) == binding['sha256'] and f.stat().st_size == binding['bytes']
with zipfile.ZipFile(fixture) as z:
    assert hashlib.sha256(z.read('assets/source.apk')).hexdigest() == sha(source)
    assert hashlib.sha256(z.read('classes.dex')).hexdigest() == prep['fixtureDex']['sha256']
if phase == 'before':
    assert sha(fixture) == prep['baselineFixture']['sha256']
    shutil.copyfile(fixture, p/'native-owned/baseline-fixture.apk')
    (p/'native-owned/baseline-fixture.apk').chmod(0o400)
with (p/('native-'+phase+'.claim')).open('x') as f:
    f.write('One frozen actual Lamp callback execution\n')
    f.flush()
    os.fsync(f.fileno())
try:
    ack = adb('install', '-r', str(fixture)).stdout
    (p/('native-'+phase+'-install.log')).write_bytes(ack)
    assert b'Success' in ack
    adb('shell', 'am', 'start', '-W', '-n', package+'/.LampStepActivity')
    deadline = time.monotonic()+35
    report = None
    while time.monotonic() < deadline:
        result = adb('shell', 'cat', '/sdcard/Android/data/'+package+'/files/report.json',
                     check=False, timeout=8)
        if result.returncode == 0:
            try:
                report = json.loads(result.stdout)
                break
            except json.JSONDecodeError:
                pass
        time.sleep(.3)
    assert report is not None
    (p/('native-'+phase+'-report.json')).write_text(json.dumps(report, indent=2)+'\n')
    assert report['error'] is None and report['tests'] == 12 and report['sdk'] == 29
    assert report['setupFailures'] == 0, 'Setup errors are excluded from product baseline'
    assert report['actualEmbeddedOrigin'] and report['exactCallbackSignatures']
    assert report['ownerHandlerRetired'] and report['unexpectedStateFunctions'] == 0
    assert not report['internetPermission']
    assert all(report[k] is False for k in ('targetContextPassed', 'targetComposeRendered',
                                          'targetLampStarted', 'targetSaveInvoked'))
    assert report['sourceSha256'] == sha(source) and report['sourceBytes'] == source.stat().st_size
    assert [c['name'] for c in report['cases']] == prep['caseNames']
    expected = ['100', '5', '91', '5', '100', '6', '100', '0', '91', '0', '100', '1']
    before = ['5', '100', '91', '5', '100', '6', '0', '100', '91', '0', '100', '1']
    inputs = ['2147483647', '-2147483648', '90', '5', '100', '',
              '2147483647', '-2147483648', '90', '0', '100', '']
    plus = [True, False, True, False, True, True]*2
    for i, case in enumerate(report['cases']):
        actual = before[i] if phase == 'before' else expected[i]
        good = actual == expected[i]
        assert case['input'] == inputs[i] and case['expected'] == expected[i]
        assert case['actual'] == actual and case['passed'] == good
        assert case['control'] == ('BRIGHTNESS' if i < 6 else 'WARMTH')
        assert case['operation'] == ('plus' if plus[i] else 'minus')
        assert case['handler'] == report['plusHandler' if plus[i] else 'minusHandler']
        assert case['draftReads'] == case['draftWrites'] == case['errorWrites'] == 1
        assert case['errorReads'] == case['unexpectedStateFunctions'] == 0
        assert case['errorAfter'] is None and case['invocationError'] is None
        assert case['unitReturned']
        assert case['errors'] == ([] if good else [
            'Literal draft expected='+expected[i]+' actual='+actual])
    assert report['failures'] == (4 if phase == 'before' else 0)
    assert report['ok'] == (phase == 'after')
    print(json.dumps({'phase': phase, 'tests': 12, 'literalFailures': report['failures'],
                      'controls': 8, 'actualCallbacks': True, 'ownerHandlerRetired': True}))
finally:
    if adb('shell', 'pm', 'path', package, check=False).stdout.strip():
        result = adb('uninstall', package, check=False)
        (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
    retirement = {
        'packageAbsent': not adb('shell', 'pm', 'path', package, check=False).stdout.strip(),
        'processAbsent': not adb('shell', 'pidof', package, check=False).stdout.strip(),
        'externalDirectoryAbsent': adb('shell', 'test', '!', '-e',
                                       '/sdcard/Android/data/'+package, check=False).returncode == 0,
    }
    (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement, indent=2)+'\n')
    assert all(retirement.values())
