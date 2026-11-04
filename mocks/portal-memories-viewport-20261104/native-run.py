from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,time,os,shutil,datetime
p=Path('/private/tmp/portal-memories-viewport-20261104-124417')
phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.memoriesviewportfixture'
fixture=p/'native-owned/memories-viewport-fixture.apk'
prep=json.loads((p/'native-owned/preparation-verification.json').read_text())
source=p/('before-debug-1.156.apk' if phase=='before' else 'candidate-debug-1.157.apk')
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
 f.write('Single frozen actual Memories viewport execution\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout
 (p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.MemoriesViewportActivity')
 deadline=time.monotonic()+48;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.4)
 assert report is not None
 (p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['caseCount']==3 and report['sdk']==29
 assert report['state']['error'] is None and report['state']['complete']
 assert report['unknownRequests']==0 and report['webViewDestroyed']
 assert report['sourceApkSha256']==sha(source) and report['sourceApkBytes']==source.stat().st_size
 assert [c['name'] for c in report['tests']]==prep['caseNames']
 with zipfile.ZipFile(source) as z:
  for name,value in report['assetHashes'].items():assert hashlib.sha256(z.read('assets/screensaver/'+name)).hexdigest()==value
  assert {'styles.css','lib-engine.js','modes.js','mode-impls.js'}<=set(report['assetHashes'])
 failed=[]
 for index,c in enumerate(report['tests']):
  assert c['photoCount']==3 and c['actualMode']=='SS.MODES.memories.run'
  assert c['root']==({'w':400,'h':240} if index<2 else {'w':800,'h':480})
  boxes=c['boxes']; assert len(boxes)==3
  fits=all(b['w']>24 and b['h']>24 and b['x']>=-.04 and b['y']>=-.04 and b['x']+b['w']<=c['root']['w']+.04 and b['y']+b['h']<=c['root']['h']+.04 for b in boxes)
  assert c['checks']['contained']==fits
  if index==0:assert all(value for key,value in c['checks'].items() if key!='contained')
  if index==1:assert c['checks']['currentMeasured']
  if index==2:
   literal=prep['gates']['originalLiteralBoxes']
   exact=all(abs(b[key]-literal[i][j])<.04 for i,b in enumerate(boxes) for j,key in enumerate(('x','y','w','h')))
   assert c['checks']['literalGeometry']==exact and exact and fits
  good=all(c['checks'].values());assert c['ok']==good
  if not good:failed.append(c['name'])
 assert report['failures']==len(failed)
 if phase=='before':assert failed==prep['expectedBaselineFailNames'] and not report['ok']
 else:assert not failed and report['ok']
 adb('pull','/sdcard/Android/data/'+package+'/files/fullsize-software.png',str(p/('native-'+phase+'-fullsize-software.png')))
 print(json.dumps({'phase':phase,'tests':3,'failures':len(failed),'actualPackagedMode':True,'sameFrozenFixture':True,'unknownRequests':0,'webViewDestroyed':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False)
  (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),
  'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),
  'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n')
 assert all(retirement.values())
