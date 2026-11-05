from pathlib import Path
import sys, subprocess, json, hashlib, os

P = Path('/private/tmp/portal-immich-album-identity-20261105-011043')
N = P / 'native'
phase = sys.argv[1]
assert phase in ('red', 'green')
tools = Path('/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0')
android = Path('/opt/homebrew/share/android-commandlinetools/platforms/android-36/android.jar')
java = N / 'ImmichAlbumIdentityProbe.java'
source = (Path('/private/tmp/portal-cinema-viewport-20261105-004614/candidate-debug-1.187.apk')
          if phase == 'red' else P / 'candidate-debug-1.188.apk')
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
assert digest(java) == '5d861e0457c133dd0b4f21ea70a2201f7a12e078a11853dc4cec36b44ceffd99'
if phase == 'red':
    (N / 'classes').mkdir()
    subprocess.run([
        '~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home/bin/javac',
        '-source', '8', '-target', '8', '-classpath', str(android),
        '-d', str(N / 'classes'), str(java)
    ], check=True, capture_output=True, timeout=20)
    classes = sorted((N / 'classes').glob('*.class'))
    assert len(classes) > 1
    subprocess.run([
        str(tools / 'd8'), '--lib', str(android), '--min-api', '29',
        '--output', str(N / 'probe.jar'), *map(str, classes)
    ], check=True, capture_output=True, timeout=20)
    frozen = {'javaSha256': digest(java), 'probeJarSha256': digest(N / 'probe.jar'),
              'driverSha256': digest(Path(__file__)), 'compiledClassCount': len(classes),
              'literalCases': 8, 'expectedBaselineFailures': 5, 'expectedBaselineControls': 3}
    (N / 'frozen-source.json').write_text(json.dumps(frozen, indent=2) + '\n')
else:
    frozen = json.loads((N / 'frozen-source.json').read_text())
    assert frozen['javaSha256'] == digest(java)
    assert frozen['probeJarSha256'] == digest(N / 'probe.jar')
    assert frozen['driverSha256'] == digest(Path(__file__))

def adb(*args):
    result = subprocess.run(['adb', '-s', '192.168.0.70:5555', *args],
                            capture_output=True, timeout=45)
    assert result.returncode == 0, result.stderr.decode('utf8', 'replace')
    return result.stdout

assert adb('shell', 'getprop', 'ro.serialno').decode().strip() == 'FAMILY_PORTAL_SERIAL'
remote = '/data/local/tmp/portal-immich-album-identity-20261105-011043-' + phase
exists = subprocess.run(['adb', '-s', '192.168.0.70:5555', 'shell', 'test', '-e', remote],
                        capture_output=True, timeout=15)
assert exists.returncode == 1
with (N / ('run-' + phase + '.claim')).open('x') as claim:
    claim.write(remote + '\n')
    claim.flush()
    os.fsync(claim.fileno())
adb('shell', 'mkdir', '-p', remote + '/dex')
(N / ('owned-' + phase + '.json')).write_text(json.dumps({'path': remote}))
try:
    for local, filename in [(source, 'source.apk'), (N / 'probe.jar', 'probe.jar')]:
        adb('push', str(local), remote + '/' + filename)
        assert adb('shell', 'sha256sum', remote + '/' + filename).decode().split()[0] == digest(local)
        adb('shell', 'chmod', '444', remote + '/' + filename)
    raw = adb('shell', 'env', 'CLASSPATH=' + remote + '/probe.jar', 'app_process',
              '/system/bin', 'ImmichAlbumIdentityProbe', remote + '/source.apk', remote + '/dex')
    (N / ('raw-' + phase + '.txt')).write_bytes(raw)
    lines = [line for line in raw.decode().splitlines() if line.startswith('{')]
    assert len(lines) == 1
    result = json.loads(lines[0])
    assert result['api'] == 29 and result['sourceClassLoaderVerified']
    assert result['caseCount'] == 8 and result['actualNetworkRequests'] == 0
    assert result['deniedUnexpected'] == 0
    assert all(case['transportPassed'] and case['failure'] is None for case in result['cases'])
    assert result['passed'] == (3 if phase == 'red' else 8)
    receipt = {'phase': phase, 'sourceApkSha256': digest(source), 'fixture': frozen, 'result': result}
    (N / ('results-' + phase + '.json')).write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'phase': phase, 'caseCount': 8, 'passed': result['passed'],
                      'sourceApkSha256': digest(source), 'actualNetworkRequests': 0}))
finally:
    adb('shell', 'rm', '-r', remote)
    absent = subprocess.run(['adb', '-s', '192.168.0.70:5555', 'shell', 'test', '-e', remote],
                            capture_output=True, timeout=15)
    assert absent.returncode == 1
    (N / ('retirement-' + phase + '.json')).write_text(
        json.dumps({'ownedPathAbsent': True, 'processTerminal': True}, indent=2) + '\n')
