from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,time,os,shutil,datetime
p=Path('/private/tmp/portal-panorama-viewport-20261104-130107')
phase=sys.argv[1];assert phase in ('before','after')
package='com.immortal.panoramaviewportfixture'
fixture=p/'native-owned/panorama-viewport-fixture.apk'
prep=json.loads((p/'native-owned/preparation-verification.json').read_text())
source=p/('before-debug-1.157.apk' if phase=='before' else 'candidate-debug-1.158.apk')
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
 f.write('Single frozen actual Panorama viewport execution\n');f.flush();os.fsync(f.fileno())
try:
 ack=adb('install','-r',str(fixture)).stdout
 (p/('native-'+phase+'-install.log')).write_bytes(ack);assert b'Success' in ack
 adb('shell','am','start','-W','-n',package+'/.PanoramaViewportActivity')
 deadline=time.monotonic()+48;report=None
 while time.monotonic()<deadline:
  result=adb('shell','cat','/sdcard/Android/data/'+package+'/files/report.json',check=False,timeout=8)
  if result.returncode==0:
   try:report=json.loads(result.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.4)
 assert report is not None
 (p/('native-'+phase+'-report.json')).write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['caseCount']==4 and report['sdk']==29
 assert report['state']['error'] is None and report['state']['complete']
 assert report['unknownRequests']==0 and report['webViewDestroyed']
 assert report['sourceApkSha256']==sha(source) and report['sourceApkBytes']==source.stat().st_size
 assert [c['name'] for c in report['tests']]==prep['caseNames']
 with zipfile.ZipFile(source) as z:
  for name,value in report['assetHashes'].items():assert hashlib.sha256(z.read('assets/screensaver/'+name)).hexdigest()==value
  assert {'styles.css','lib-engine.js','modes.js','mode-impls.js'}<=set(report['assetHashes'])
 failed=[]
 for index,c in enumerate(report['tests']):
  assert c['actualMode']=='SS.MODES.panorama.run'
  assert c['root']==({'w':400,'h':240} if index<3 else {'w':800,'h':480})
  w,h,top,travel=(c['strip'][k] for k in ('width','height','top','travel'));W,H=c['root']['w'],c['root']['h']
  bounded=w>=W-.04 and w<=2*W+.04 and h>0 and h<=H+.04 and top>=-.04 and top+h<=H+.04 and abs(top-(H-h)/2)<.04 and travel<=.04 and travel>=-W-.04
  assert c['checks']['bounded']==bounded
  if index in (0,1,3):
   q=c['fullPhoto'];whole=q['w']>0 and q['h']>0 and q['x']>=-.04 and q['y']>=-.04 and q['x']+q['w']<=W+.04 and q['y']+q['h']<=H+.04
   assert c['checks']['wholePhoto']==whole
  if index in (0,2):assert all(value for key,value in c['checks'].items() if key not in ('bounded','wholePhoto'))
  if index==1:assert c['checks']['currentMeasured'] and c['checks']['oneLiveImage']
  if index==3:
   assert [w,h,top]==prep['gates']['literalFullsizeStrip']
   assert all(abs(c['fullPhoto'][k]-v)<.04 for k,v in zip(('x','y','w','h'),prep['gates']['literalFullsizePhoto']))
   assert all(c['checks'].values())
  good=all(c['checks'].values());assert c['ok']==good
  if not good:failed.append(c['name'])
 assert report['failures']==len(failed)
 if phase=='before':assert failed==prep['expectedBaselineFailNames'] and not report['ok']
 else:assert not failed and report['ok']
 adb('pull','/sdcard/Android/data/'+package+'/files/fullsize-software.png',str(p/('native-'+phase+'-fullsize-software.png')))
 print(json.dumps({'phase':phase,'tests':4,'failures':len(failed),'actualPackagedMode':True,'sameFrozenFixture':True,'unknownRequests':0,'webViewDestroyed':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  result=adb('uninstall',package,check=False)
  (p/('native-'+phase+'-uninstall.log')).write_bytes(result.stdout+result.stderr)
 retirement={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),
  'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),
  'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/('native-'+phase+'-retirement.json')).write_text(json.dumps(retirement,indent=2)+'\n')
 assert all(retirement.values())
