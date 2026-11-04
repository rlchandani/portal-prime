from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,time,os,shutil
p=Path('/private/tmp/portal-flip-format-20261104-130107');phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.flipformatfixture';fixture=p/'native-owned/flip-format-fixture.apk';prep=json.loads((p/'native-owned/preparation-verification.json').read_text());source=p/('before-debug-1.158.apk' if phase=='before' else 'candidate-debug-1.159.apk')
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
def adb(*args,check=True,timeout=45):return subprocess.run(['adb','-s','192.168.0.70:5555',*args],capture_output=True,check=check,timeout=timeout)
assert adb('shell','getprop','ro.serialno').stdout.decode().strip()=='FAMILY_PORTAL_SERIAL';assert not adb('shell','pm','path',package,check=False).stdout.strip()
for name,b in prep['sources'].items():assert sha(p/'native-owned'/name)==b['sha256']
with zipfile.ZipFile(fixture) as z:
 assert sha(source)==hashlib.sha256(z.read('assets/source.apk')).hexdigest();assert hashlib.sha256(z.read('classes.dex')).hexdigest()==prep['fixtureDex']['sha256']
if phase=='before':
 assert sha(fixture)==prep['baselineFixture']['sha256'];shutil.copyfile(fixture,p/'native-owned/baseline-fixture.apk');(p/'native-owned/baseline-fixture.apk').chmod(0o400)
with (p/('native-'+phase+'.claim')).open('x') as f:f.write('Single frozen actual Flip constructor run\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout;(p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.FlipFormatActivity');deadline=time.monotonic()+35;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.3)
 assert report is not None;(p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['tests']==8 and report['sdk']==29 and report['setupFailures']==0,'Fixture constructor admission failed; no product baseline accepted'
 assert report['actualEmbeddedOrigin'] and report['constructed']==report['disposed']==8 and report['allDefaultsRestored'] and report['unexpectedContextOperations']==0 and not report['internetPermission'] and not report['realClockSettingsChanged']
 assert report['sourceSha256']==sha(source) and report['sourceBytes']==source.stat().st_size and [c['name'] for c in report['cases']]==prep['caseNames']
 literal=['0105','1205','1205','1159','1305','0005','1205','2359'];baseline=['1305','0005','1205','2359','1305','0005','1205','2359'];failed=[]
 for i,c in enumerate(report['cases']):
  assert c['setupError'] is None and c['actualConstructor'] and c['formatterReadOnly'] and c['disposed'] and c['defaultsRestored'] and c['unexpectedContextOperations']==0 and c['expected']==literal[i]
  expected=baseline[i] if phase=='before' else literal[i];assert c['actual']==c['revealDigits']==c['revealPending']==c['firstResumedTickDigits']==expected
  good=expected==literal[i];assert c['passed']==good
  if not good:
   assert c['errors']==['Initialized formatter literal expected='+literal[i]+' actual='+expected,'Actual refreshForReveal literal digits','First resumed update literal digits'];failed.append(c['name'])
  else:assert not c['errors']
 assert report['failures']==len(failed)
 if phase=='before':assert failed==prep['expectedBaselineFailNames'] and not report['ok']
 else:assert not failed and report['ok']
 print(json.dumps({'phase':phase,'tests':8,'literalFailures':len(failed),'actualConstructorAndConsumers':True,'constructedDisposed':8,'defaultsRestored':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False);(p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n');assert all(retirement.values())
