from pathlib import Path
import hashlib
import json
import os
import subprocess
import time
import zipfile

p = Path('/private/tmp/portal-screensaver-next-20261104-140759')
n = p/'native-owned'
package = 'com.immortal.dissolvepixelfixture'
apk = n/'dissolve-pixel-fixture.apk'
source = p/'source-debug-1.162.apk'
prep = json.loads((n/'preparation-verification.json').read_text())
sha = lambda f: hashlib.sha256(f.read_bytes()).hexdigest()

def adb(*args, check=True, timeout=45):
    return subprocess.run(['adb', '-s', '192.168.0.70:5555', *args],
        capture_output=True, check=check, timeout=timeout)

assert adb('shell', 'getprop', 'ro.serialno').stdout.decode().strip() == 'FAMILY_PORTAL_SERIAL'
assert not adb('shell', 'pm', 'path', package, check=False).stdout.strip()
for name, binding in prep['sources'].items():
    assert sha(n/name) == binding['sha256'] and (n/name).stat().st_size == binding['bytes']
assert sha(apk) == prep['fixtureApk']['sha256']
assert sha(source) == prep['sourceApk']['sha256']
assert sha(source) == '2a1e7dae0ca82795d424a3b4811481d0609da3c811dea46c5014795c1e3acb39'
with zipfile.ZipFile(apk) as z:
    assert z.read('assets/source.apk') == source.read_bytes()
    assert hashlib.sha256(z.read('classes.dex')).hexdigest() == prep['fixtureDex']['sha256']
with (p/'native-current.claim').open('x') as f:
    f.write('One frozen controlled hardware dissolve execution\n')
    f.flush()
    os.fsync(f.fileno())
try:
    result = adb('install', '-r', str(apk))
    (p/'native-install.log').write_bytes(result.stdout + result.stderr)
    assert b'Success' in result.stdout
    adb('shell', 'am', 'start', '-W', '-n', package+'/.DissolvePixelActivity')
    report = None
    deadline = time.monotonic() + 58
    remote = '/sdcard/Android/data/'+package+'/files/'
    while time.monotonic() < deadline:
        result = adb('shell', 'cat', remote+'report.json', check=False, timeout=8)
        if result.returncode == 0:
            try:
                report = json.loads(result.stdout)
                break
            except json.JSONDecodeError:
                pass
        time.sleep(.4)
    assert report is not None
    (p/'native-report.json').write_text(json.dumps(report, indent=2)+'\n')
    assert report['error'] is None and report['ok'] and report['tests'] == 3
    assert report['failures'] == 0 and report['sdk'] == 29
    assert report['sourceSha256'] == sha(source)
    assert report['sourceBytes'] == source.stat().st_size
    assert report['actualEmbeddedStageOrigin'] and report['fixturePackage'] == package
    assert not report['internetPermission']
    assert report['unexpectedPreferenceRequests'] == report['unexpectedResolverRequests'] == report['unexpectedServiceRequests'] == 0
    assert report['retiredScenes'] == 1 and report['retirementFrameCommitted']
    assert report['inputsRecycledAfterCommittedDetach'] and report['ownerHandlerRetired']
    assert report['cleanup'] and report['currentOnlyAcceptance'] and report['noRedOrSourceChangeClaim']
    images = p/'images'
    images.mkdir(mode=0o700)
    fractions = [.25, .5, .75]
    for i, case in enumerate(report['cases']):
        fraction = fractions[i]
        assert case['name'] == 'fraction-'+str(fraction)
        assert case['fraction'] == fraction and case['passed']
        assert abs(case['incomingAlpha']-fraction) < .00001 and case['outgoingAlpha'] == 1
        assert case['actualFadePaused'] and case['sameActualFade']
        assert case['stageAttached'] and case['stageHardwareAccelerated']
        assert case['committedFramesBeforePixelCopy'] == 2 and case['pixelCopyResult'] == 0
        crop = case['windowCrop']
        assert crop[2]-crop[0] == 800 and crop[3]-crop[1] == 480
        geometry = case['geometry']
        assert geometry['passed'] and geometry['budgetUsed'] == geometry['expectedAdmittedBytes'] == geometry['literalExpectedAdmittedBytes'] == 3072000
        assert len(geometry['layers']) == 2
        for photo, layer in enumerate(geometry['layers']):
            assert layer['photo'] == photo and layer['passed'] and layer['sameBitmap']
            assert all(abs(layer[key]-value) < .02 for key, value in
                {'left':256, 'top':0, 'right':544, 'bottom':480}.items())
            assert abs(layer['scaleX']-.6) < .00001 and abs(layer['scaleY']-.6) < .00001
        for mode in ('software', 'hardware'):
            capture = case[mode]
            assert all(capture[key] for key in ('passed', 'opaque', 'mattePass', 'noDarkDip'))
            assert capture['width'] == 800 and capture['height'] == 480 and capture['fraction'] == fraction
            assert capture['transparentPixels'] == capture['badMatte'] == 0
            assert capture['samplePassCount'] == len(capture['samples']) == 5
            assert all(sample['passed'] and sample['maxChannelTolerance'] == 3 for sample in capture['samples'])
            expected_name = f'fraction-{i}-{mode}.png'
            assert capture['png'] == expected_name
            adb('pull', remote+expected_name, str(images/expected_name))
    print(json.dumps({'controlledFractions':3, 'hardwareCaptures':3,
        'softwareCaptures':3, 'literalSamplesPerPath':15, 'normalStageRetirements':1,
        'noProductChangeOrFreshMotionVerdict':True}))
finally:
    if adb('shell', 'pm', 'path', package, check=False).stdout.strip():
        result = adb('uninstall', package, check=False)
        (p/'native-uninstall.log').write_bytes(result.stdout + result.stderr)
    retirement = {
        'packageAbsent':not adb('shell', 'pm', 'path', package, check=False).stdout.strip(),
        'processAbsent':not adb('shell', 'pidof', package, check=False).stdout.strip(),
        'externalDirectoryAbsent':adb('shell', 'test', '!', '-e',
            '/sdcard/Android/data/'+package, check=False).returncode == 0,
    }
    (p/'native-retirement.json').write_text(json.dumps(retirement, indent=2)+'\n')
    assert all(retirement.values())
