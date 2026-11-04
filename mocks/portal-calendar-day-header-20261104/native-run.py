from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,time,os,shutil,datetime
p=Path('/private/tmp/portal-calendar-day-header-20261104-121512')
phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.dayheaderfixture'
fixture=p/'native-owned/day-header-fixture.apk'
prep=json.loads((p/'native-owned/preparation-verification.json').read_text())
source=p/('before-debug-1.155.apk' if phase=='before' else 'candidate-debug-1.156.apk')
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def adb(*args,check=True,timeout=45):
 return subprocess.run(['adb','-s','192.168.0.70:5555',*args],capture_output=True,check=check,timeout=timeout)
assert adb('shell','getprop','ro.serialno').stdout.decode().strip()=='FAMILY_PORTAL_SERIAL'
assert not adb('shell','pm','path',package,check=False).stdout.strip()
for name,value in prep['sources'].items():assert sha(p/'native-owned'/name)==value['sha256']
with zipfile.ZipFile(fixture) as z:
 assert sha(source)==hashlib.sha256(z.read('assets/source.apk')).hexdigest()
 assert hashlib.sha256(z.read('classes.dex')).hexdigest()==prep['fixtureDex']['sha256']
if phase=='before':
 assert sha(fixture)==prep['baselineFixture']['sha256']
 shutil.copyfile(fixture,p/'native-owned/baseline-fixture.apk')
 (p/'native-owned/baseline-fixture.apk').chmod(0o400)
with (p/('native-'+phase+'.claim')).open('x') as f:
 f.write('Single frozen actual private header execution\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout
 (p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.DayHeaderActivity')
 deadline=time.monotonic()+48;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.4)
 assert report is not None
 (p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['tests']==6 and report['sdk']==29
 assert report['actualEmbeddedControllerOrigin'] and report['actualPrivateDayHeader'] and report['allDefaultsRestored']
 assert not report['internetPermission'] and not report['realClockChanged']
 assert report['constructorCalls']==report['contextsServicesProvidersPrefsPassed']==0
 assert report['sourceSha256']==sha(source) and report['sourceBytes']==source.stat().st_size
 assert [c['name'] for c in report['cases']]==prep['caseNames']
 failed=[]
 for index,c in enumerate(report['cases']):
  assert c['defaultsRestored'] and not c['realClockChanged'] and c['eventConstructedFromClearedIndependentDateFields']
  assert 0<=c['driftMillis']<120000 and c['constructorCalls']==c['contextsServicesProvidersPrefsPassed']==0
  def local(stamp,offset):return datetime.datetime.fromtimestamp((stamp+offset)/1000,datetime.timezone.utc)
  now=local(c['invokeBeforeMillis'],c['sampleOffsetMillis'])
  event=local(c['eventMillis'],c['eventOffsetMillis'])
  delta=[1,1,0,0,1,8][index]
  assert event.date()==now.date()+datetime.timedelta(days=delta) and event.hour==12 and event.minute==0
  if index<4:
   fall=index in (0,2)
   assert now.hour==(0 if fall else 23) and 30<=now.minute<32
   assert c['plus24OffsetMillis']-c['sampleOffsetMillis']==(-3600000 if fall else 3600000)
  expected=['Tomorrow','Tomorrow','Today','Today','Tomorrow',None][index]
  good=c['actual']==expected if expected is not None else bool(c['actual']) and c['actual'] not in ('Today','Tomorrow')
  assert c['passed']==good and (not c['errors'])==good
  if not good:
   assert len(c['errors'])==1 and c['errors'][0].startswith('Literal header expected=Tomorrow actual=')
   failed.append(c['name'])
 assert report['failures']==len(failed)
 if phase=='before':assert failed==prep['expectedBaselineFailNames'] and not report['ok']
 else:assert not failed and report['ok']
 print(json.dumps({'phase':phase,'tests':6,'failures':len(failed),'actualPrivateHeader':True,'defaultsRestored':True,'realClockUnchanged':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False)
  (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),
  'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),
  'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n')
 assert all(retirement.values())
