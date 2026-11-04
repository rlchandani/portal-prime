from pathlib import Path
import hashlib,json,os,subprocess,time,zipfile
p=Path('/private/tmp/portal-photo-presentation-audit-20261104-121512');n=p/'native-owned';package='com.immortal.attachedphotofixture';apk=n/'attached-photo-fixture.apk';source=p/'source-debug-1.155.apk';prep=json.loads((n/'preparation-verification.json').read_text())
def sha(f):return hashlib.sha256(f.read_bytes()).hexdigest()
def adb(*args,check=True,timeout=45):return subprocess.run(['adb','-s','192.168.0.70:5555',*args],capture_output=True,check=check,timeout=timeout)
assert adb('shell','getprop','ro.serialno').stdout.decode().strip()=='FAMILY_PORTAL_SERIAL'
assert not adb('shell','pm','path',package,check=False).stdout.strip()
for name,b in prep['sources'].items():assert sha(n/name)==b['sha256']
assert sha(apk)==prep['fixtureApk']['sha256'] and sha(source)==prep['sourceApk']['sha256']
with zipfile.ZipFile(apk) as z:
 assert z.read('assets/source.apk')==source.read_bytes()
 assert hashlib.sha256(z.read('classes.dex')).hexdigest()==prep['fixtureDex']['sha256']
with (p/'native-current.claim').open('x') as f:f.write('Single frozen current attached Stage hardware execution\n');f.flush();os.fsync(f.fileno())
try:
 r=adb('install','-r',str(apk));(p/'native-install.log').write_bytes(r.stdout+r.stderr);assert b'Success' in r.stdout
 adb('shell','am','start','-W','-n',package+'/.AttachedPhotoActivity')
 report=None;deadline=time.monotonic()+58;remote='/sdcard/Android/data/'+package+'/files/'
 while time.monotonic()<deadline:
  r=adb('shell','cat',remote+'report.json',check=False,timeout=8)
  if r.returncode==0:
   try:report=json.loads(r.stdout);break
   except json.JSONDecodeError:pass
  time.sleep(.4)
 assert report is not None;(p/'native-report.json').write_text(json.dumps(report,indent=2)+'\n')
 assert report['error'] is None and report['tests']==3 and report['sdk']==29 and report['ok'] and report['failures']==0
 assert report['sourceSha256']==sha(source) and report['sourceBytes']==source.stat().st_size and report['actualEmbeddedStageOrigin']
 assert report['fixturePackage']==package and not report['internetPermission']
 assert report['unexpectedPreferenceRequests']==report['unexpectedResolverRequests']==report['unexpectedServiceRequests']==0
 assert report['retiredScenes']==3 and report['cleanup'] and report['currentOnlyAcceptance'] and report['noRedOrSourceChangeClaim']
 assert [c['name'] for c in report['cases']]==prep['caseNames']
 images=p/'images';images.mkdir(mode=0o700)
 for c in report['cases']:
  assert c['passed'] and c['stageAttached'] and c['stageHardwareAccelerated'] and c['committedFramesBeforePixelCopy']==2 and c['pixelCopyResult']==0
  assert c['width']==800 and c['height']==480 and c['noMotion'] and c['inactiveHost'] and c['firstPresentationOnly']
  assert c['windowCrop'][2]-c['windowCrop'][0]==800 and c['windowCrop'][3]-c['windowCrop'][1]==480
  for mode in ('software','hardware'):
   v=c[mode];assert all(v[x] for x in ('passed','geometryPass','sameBitmapIdentity','wholePhotos','photoPass','opaque','mattePass','borderPass'))
   assert v['badMatte']==v['transparentPixels']==0 and v['budgetUsed']==v['expectedAdmittedBytes']
   assert v['cornerPassCount']==len(v['corners']) and all(x['passed'] for x in v['corners']) and all(x['passed'] for x in v['edges'])
   adb('pull',remote+c['name']+'-'+mode+'.png',str(images/(c['name']+'-'+mode+'.png')))
  assert c['software']['geometry']==c['hardware']['geometry'] and c['software']['budgetUsed']==c['hardware']['budgetUsed']
 print(json.dumps({'currentOnly':True,'nativeCases':3,'hardwareWindows':3,'softwareCaptures':3,'cornersPerPath':16,'edgesPerPath':16,'framesPerCase':2,'noAppSourceOrVersionChange':True}))
finally:
 if adb('shell','pm','path',package,check=False).stdout.strip():
  r=adb('uninstall',package,check=False);(p/'native-uninstall.log').write_bytes(r.stdout+r.stderr)
 j={'packageAbsent':not adb('shell','pm','path',package,check=False).stdout.strip(),'processAbsent':not adb('shell','pidof',package,check=False).stdout.strip(),'externalDirectoryAbsent':adb('shell','test','!','-e','/sdcard/Android/data/'+package,check=False).returncode==0}
 (p/'native-retirement.json').write_text(json.dumps(j,indent=2)+'\n');assert all(j.values())
