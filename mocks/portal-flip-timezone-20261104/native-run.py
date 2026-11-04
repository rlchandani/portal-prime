from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys, time, zipfile
p=Path('/private/tmp/portal-flip-timezone-20261104-132030')
phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.fliptimezonefixture'
fixture=p/'native-owned/flip-timezone-fixture.apk'
prep=json.loads((p/'native-owned/preparation-verification.json').read_text())
source=p/('before-debug-1.159.apk' if phase=='before' else 'candidate-debug-1.160.apk')
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
def adb(*args,check=True,timeout=45):
 return subprocess.run(['adb','-s','192.168.0.70:5555',*args],capture_output=True,check=check,timeout=timeout)
assert adb('shell','getprop','ro.serialno').stdout.decode().strip()=='FAMILY_PORTAL_SERIAL'
assert not adb('shell','pm','path',package,check=False).stdout.strip()
for name,b in prep['sources'].items():
 f=p/'native-owned'/name;assert sha(f)==b['sha256'] and f.stat().st_size==b['bytes']
with zipfile.ZipFile(fixture) as z:
 assert sha(source)==hashlib.sha256(z.read('assets/source.apk')).hexdigest()
 assert hashlib.sha256(z.read('classes.dex')).hexdigest()==prep['fixtureDex']['sha256']
if phase=='before':
 assert sha(fixture)==prep['baselineFixture']['sha256']
 shutil.copyfile(fixture,p/'native-owned/baseline-fixture.apk')
 (p/'native-owned/baseline-fixture.apk').chmod(0o400)
with (p/('native-'+phase+'.claim')).open('x') as f:
 f.write('Single frozen actual retained Flip timezone run\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout
 (p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.FlipTimezoneActivity')
 deadline=time.monotonic()+35;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.3)
 assert report is not None
 (p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['tests']==8 and report['sdk']==29 and report['setupFailures']==0,'Constructor/setup failure is not an accepted product baseline'
 assert report['actualEmbeddedOrigin'] and report['constructed']==report['disposed']==8
 assert report['allDefaultsRestored'] and report['unexpectedContextOperations']==0
 assert not report['internetPermission'] and not report['realClockSettingsChanged']
 assert report['sourceSha256']==sha(source) and report['sourceBytes']==source.stat().st_size
 assert [c['name'] for c in report['cases']]==prep['caseNames']
 initial=['0105','1305','0635','1835','0105','1305','0105','0105']
 target=['0635','1835','0105','1305','0105','1305','0105','0105']
 zones=['UTC','UTC','GMT+05:30','GMT+05:30','UTC','UTC','UTC','UTC']
 current=['GMT+05:30','GMT+05:30','UTC','UTC','UTC','UTC','GMT+05:30','GMT+05:30']
 failed=[]
 for i,c in enumerate(report['cases']):
  assert c['setupError'] is None and c['actualConstructor'] and c['formatterReadOnly']
  assert c['disposed'] and c['defaultsRestored'] and c['unexpectedContextOperations']==0
  assert c['input']=='2030-01-01 13:05 UTC'
  assert c['expectedCurrent']==target[i] and c['expectedReturn']==initial[i]
  assert c['initialZone']==zones[i] and c['currentZone']==current[i] and c['initialFormatterZone']==zones[i]
  expected=initial[i] if phase=='before' else target[i]
  assert c['firstResumedTickDigits']==expected
  assert c['pendingDigits']==initial[i]
  if i<6:
   assert c['guard']==0 and c['formattedAfterUpdate']==expected
   assert c['afterUpdateFormatterZone']==(zones[i] if phase=='before' else current[i])
   assert c['afterRevealFormatterZone']==zones[i]
   assert c['revealDigits']==c['formattedAfterReveal']==initial[i]
  else:
   assert c['guard']==i-5 and c['afterUpdateFormatterZone']==zones[i]
   assert c['revealDigits'] is None and c['formattedAfterUpdate'] is None and c['afterRevealFormatterZone'] is None
  good=phase=='after' or i>=4;assert c['passed']==good
  assert c['errors']==([] if good else ['Actual first resumed update literal current-zone digits'])
  if not good:failed.append(c['name'])
 assert report['failures']==len(failed)
 if phase=='before':assert failed==prep['expectedBaselineFailNames'] and not report['ok']
 else:assert not failed and report['ok']
 print(json.dumps({'phase':phase,'tests':8,'literalFailures':len(failed),'controls':4,'actualConstructorAndBothConsumers':True,'constructedDisposed':8,'defaultsRestored':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False)
  (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),
             'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),
             'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n')
 assert all(retirement.values())
