from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,time,os,shutil,datetime
p=Path('/private/tmp/portal-cinema-viewport-20261105-004614')
phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.cinemaviewportfixture'
fixture=p/'native-owned/cinema-viewport-fixture.apk'
prep=json.loads((p/'native-owned/preparation-verification.json').read_text())
source=Path('/private/tmp/portal-immich-asset-admission-20261105-003337/candidate-debug-1.186.apk') if phase=='before' else p/'candidate-debug-1.187.apk'
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def adb(*args,check=True,timeout=45):
 return subprocess.run(['adb','-s','192.168.0.70:5555',*args],capture_output=True,check=check,timeout=timeout)
assert sha(p/'native-run.py')==json.loads((p/'native-driver-binding.json').read_text())['sha256']
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
 f.write('Single frozen actual Cinema viewport execution\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout
 (p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.CinemaViewportActivity')
 deadline=time.monotonic()+48;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.4)
 assert report is not None
 (p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['caseCount']==5 and report['sdk']==29
 assert report['state']['error'] is None and report['state']['complete']
 assert report['unknownRequests']==0 and report['webViewDestroyed']
 assert report['sourceApkSha256']==sha(source) and report['sourceApkBytes']==source.stat().st_size
 assert [c['name'] for c in report['tests']]==['originalFullsizeLandscape','sameSceneLandscapeShrink','sameSceneLandscapePortrait','futurePhotoAtShrunkBounds','originalFullsizeWideControl']
 with zipfile.ZipFile(source) as z:
  for name,value in report['assetHashes'].items():assert hashlib.sha256(z.read('assets/screensaver/'+name)).hexdigest()==value
  assert {'styles.css','lib-engine.js','modes.js','mode-impls.js'}<=set(report['assetHashes'])
 failed=[]
 literal=[(1600,1000,215,0,1170,780),(640,400,86,0,468,312),(800,1200,0,201.333333333333,800,533.333333333333),(640,400,86,0,468,312),(1600,1000,0,0,1600,780)]
 for index,c in enumerate(report['tests']):
  assert c['actualMode']=='SS.MODES.cinema.run'
  W,H,x,y,w,h=literal[index];expected={'rootW':W,'rootH':H,'x':x,'y':y,'w':w,'h':h};assert c['expected']==expected
  b=c['actual'];root=c['root'];scope=c['scope'];near=lambda a,b:abs(a-b)<.05
  same=all(near(b[k],expected[k]) for k in ('x','y','w','h'))
  within=b['x']>=-.05 and b['y']>=-.05 and b['x']+b['w']<=scope['w']+.05 and b['y']+b['h']<=scope['h']+.05
  measured=near(root['w'],W) and near(root['h'],H)
  assert c['checks']['literalGeometry']==same and c['checks']['withinScope']==within and c['checks']['measuredRoot']==measured
  assert all(v for k,v in c['checks'].items() if k not in ('literalGeometry','withinScope'))
  good=all(c['checks'].values());assert c['ok']==good
  if not good:failed.append(c['name'])
 assert report['failures']==len(failed)
 if phase=='before':assert failed==['sameSceneLandscapeShrink','sameSceneLandscapePortrait','futurePhotoAtShrunkBounds'] and not report['ok']
 else:assert not failed and report['ok']
 adb('pull','/sdcard/Android/data/'+package+'/files/fullsize-software.png',str(p/('native-'+phase+'-fullsize-software.png')))
 print(json.dumps({'phase':phase,'tests':5,'failures':len(failed),'actualPackagedMode':True,'sameFrozenFixture':True,'unknownRequests':0,'webViewDestroyed':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False)
  (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),
  'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),
  'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n')
 assert all(retirement.values())
