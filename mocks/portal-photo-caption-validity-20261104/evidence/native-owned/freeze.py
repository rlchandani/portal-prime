from pathlib import Path
import hashlib,json,os,sys,zipfile,subprocess,datetime
n=Path(__file__).resolve().parent;out=n/'preparation-verification.json'
assert not out.exists()
def rec(f):return {'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'bytes':f.stat().st_size}
source=Path(sys.argv[1]);sdk=Path(sys.argv[2]);apk=n/'caption-date-fixture.apk'
with zipfile.ZipFile(apk) as z:
 assert z.read('assets/source.apk')==source.read_bytes()
 assert z.read('classes.dex')==(n/'frozen-dex/classes.dex').read_bytes()
perms=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','permissions',str(apk)],text=True);assert 'uses-permission' not in perms
j=json.loads((n/'source-admission.json').read_text());j.update({'ok':True,'preparedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'rootFullyReadBeforeCompilation':True,'sources':{name:rec(n/name) for name in ['PhotoCaptionDateActivity.java','AndroidManifest.xml','build.sh','repack.sh','freeze.py','RUN.md','source-admission.json']},'fixtureDex':rec(n/'frozen-dex/classes.dex'),'sourceApk':rec(source),'sourceApkExact':True,'fixtureApk':rec(apk),'manifestZeroPermissions':True,'permissionsDump':perms})
out.write_text(json.dumps(j,indent=2)+'\n')
for name in j['sources']:os.chmod(n/name,0o500 if name.endswith('.sh') else 0o400)
os.chmod(n/'frozen-dex/classes.dex',0o400)
print(json.dumps({'fixture':j['fixtureApk'],'dex':j['fixtureDex'],'source':j['sourceApk'],'zeroPermissions':True}))
