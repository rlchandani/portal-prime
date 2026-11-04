from pathlib import Path
import hashlib,json,os,sys,zipfile,subprocess,datetime
n=Path(__file__).resolve().parent
p=n/'preparation-verification.json'
if p.exists(): raise SystemExit('Preparation already frozen; do not regenerate')
def rec(p): return {'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
b=Path(sys.argv[1]);wrapper=n/'classic-local-fixture.apk'
with zipfile.ZipFile(wrapper) as z: dex=z.read('classes.dex');source=z.read('assets/source.apk')
assert source==b.read_bytes() and dex==(n/'frozen-dex/classes.dex').read_bytes()
sdk=Path(sys.argv[2]) if len(sys.argv)>2 else Path('/opt/homebrew/share/android-commandlinetools')
perms=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','permissions',str(wrapper)],text=True)
assert 'uses-permission' not in perms
j=json.loads((n/'source-authoring.json').read_text());j.update({'ok':True,'preparedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sources':{name:rec(n/name) for name in ['ClassicLocalActivity.java','AndroidManifest.xml','build.sh','repack.sh','RUN.md','freeze.py','source-authoring.json']},'fixtureDex':{'sha256':hashlib.sha256(dex).hexdigest(),'bytes':len(dex)},'baselineFixture':rec(wrapper),'embeddedBaseline':rec(b),'embeddedBaselineExact':True,'baselineAndFrozenFixtureDexByteIdentical':True,'manifestZeroPermissions':True,'permissionsDump':perms})
p.write_text(json.dumps(j,indent=2)+'\n');os.chmod(p,0o600)
for name in j['sources']:os.chmod(n/name,0o500 if name.endswith('.sh') else 0o400)
os.chmod(n/'frozen-dex/classes.dex',0o400)
print(json.dumps({'preparation':rec(p),'java':j['sources']['ClassicLocalActivity.java'],'fixtureDex':j['fixtureDex'],'baselineFixture':j['baselineFixture'],'embeddedBaseline':j['embeddedBaseline']},indent=2))
