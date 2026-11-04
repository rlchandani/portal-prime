from pathlib import Path
import sys,json,hashlib,subprocess,zipfile,os
p=Path(__file__).parent;source=Path(sys.argv[1]);sdk=Path(sys.argv[2])
def binding(f):
 data=f.read_bytes();return {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
a=json.loads((p/'source-admission.json').read_text())
for n,v in a['sources'].items():assert binding(p/n)==v
assert binding(source)=={'sha256':'23bbd0067f919ef221ba67d40b18f4d3e69e02834cd77fce8640c3b2e2e4057a','bytes':46745646}
f=p/'lamp-step-fixture.apk';d=p/'frozen-dex/classes.dex'
with zipfile.ZipFile(f)as z:
 assert len(z.namelist())==len(set(z.namelist()))
 assert z.read('classes.dex')==d.read_bytes()
 assert z.read('assets/source.apk')==source.read_bytes()
permissions=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','permissions',str(f)],text=True)
assert 'uses-permission' not in permissions
r=dict(a);r.update(state='root-compiled-baseline-ready-unexecuted',ok=True,fixtureDex=binding(d),baselineFixture=binding(f),sourceApk=binding(source),manifestZeroPermissions=True,permissionsDump=permissions,fixtureDexPendingRootCompilation=False)
r['sources']['source-admission.json']=binding(p/'source-admission.json')
(p/'preparation-verification.json').write_text(json.dumps(r,indent=2)+'\n');os.chmod(p/'preparation-verification.json',0o600)
print(json.dumps({'prepared':True,'fixtureDex':r['fixtureDex'],'baselineFixture':r['baselineFixture'],'sourceApkExact':True,'zeroPermissions':True}))
