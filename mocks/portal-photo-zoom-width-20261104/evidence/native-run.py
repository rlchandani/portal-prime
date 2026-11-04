from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys, time, zipfile
p = Path('/private/tmp/portal-visible-ux-20261104-134216')
phase = sys.argv[1]
assert phase in ('before', 'after')
package = 'com.immortal.photozoomwidthfixture'
fixture = p/'native-owned/photo-zoom-width-fixture.apk'
prep = json.loads((p/'native-owned/preparation-verification.json').read_text())
source = p/('before-debug-1.160.apk' if phase == 'before' else 'candidate-debug-1.161.apk')
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
    f.write('One frozen actual photo zoom layout execution\n')
    f.flush()
    os.fsync(f.fileno())
try:
    ack = adb('install', '-r', str(fixture)).stdout
    (p/('native-'+phase+'-install.log')).write_bytes(ack)
    assert b'Success' in ack
    adb('shell', 'am', 'start', '-W', '-n', package+'/.PhotoZoomWidthActivity')
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
    assert report['error'] is None and report['tests'] == 3 and report['sdk'] == 29
    assert report['setupFailures'] == 0, 'Setup errors are excluded from product baseline'
    assert report['actualEmbeddedOrigin'] and report['retired'] == 3 and report['cleanup']
    assert report['unexpectedContextOperations'] == report['callbacks'] == 0
    assert not report['internetPermission']
    assert report['sourceSha256'] == sha(source) and report['sourceBytes'] == source.stat().st_size
    assert [c['name'] for c in report['cases']] == prep['caseNames']
    dp = lambda value: int(value*report['density'])
    for index, case in enumerate(report['cases']):
        width = dp([320, 480, 800][index])
        expected = min(dp(620), width-2*dp(32))
        actual = dp(620) if phase == 'before' else expected
        assert case['actualConstructors'] and case['setupError'] is None and case['retired']
        assert case['unexpectedContextOperations'] == case['callbacks'] == 0
        assert case['rootWidthPx'] == width
        assert case['paddingLeftPx'] == case['paddingRightPx'] == dp(32)
        assert case['editorLeftPx'] == dp(32) and case['editorRightPx'] == width-dp(32)
        assert case['editorWidthPx'] == width-2*dp(32)
        assert case['seekLeftPx'] == 0 and case['seekRightPx'] == case['seekWidthPx'] == actual
        assert case['expectedSeekWidthPx'] == expected
        good = phase == 'after' or index == 2
        assert case['fullyContained'] == case['passed'] == good
        assert case['wide620Control'] == (index == 2)
        assert case['errors'] == ([] if good else [
            'Photo zoom fully within editor horizontal bounds',
            'Literal capped slider width min(620 dp, available)',
            'Repeated visible editor retains expected width',
        ])
    assert report['failures'] == (2 if phase == 'before' else 0)
    assert report['ok'] == (phase == 'after')
    print(json.dumps({'phase': phase, 'tests': 3, 'literalFailures': report['failures'],
                      'wideControl': True, 'actualConstructors': True, 'retired': 3}))
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
