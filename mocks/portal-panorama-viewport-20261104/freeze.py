from pathlib import Path
import sys,json,hashlib,subprocess,zipfile
p=Path(__file__).parent;source=Path(sys.argv[1]);sdk=Path(sys.argv[2])
def binding(f):
 data=f.read_bytes();return {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
admission=json.loads((p/'source-admission.json').read_text())
for name,value in admission['sources'].items():assert binding(p/name)==value
assert binding(source)=={'sha256':'bea8a85790f829d86fa401e93afe09e9aae6f9dc5f5520f6820033ed137443b2','bytes':46745522}
fixture=p/'panorama-viewport-fixture.apk';dex=p/'frozen-dex/classes.dex'
with zipfile.ZipFile(fixture) as z:
 assert len(z.namelist())==len(set(z.namelist()))
 assert z.read('classes.dex')==dex.read_bytes()
 assert z.read('assets/source.apk')==source.read_bytes()
 assert z.read('assets/fixture.html')==(p/'fixture.html').read_bytes()
permissions=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt2'),'dump','permissions',str(fixture)],text=True)
assert 'uses-permission' not in permissions
result=dict(admission)
result.update(ok=True,rootFullyReadBeforeCompilation=True,fixtureDex=binding(dex),
 baselineFixture=binding(fixture),sourceApk=binding(source),manifestZeroPermissions=True,
 permissionsDump=permissions)
result['sources']={name:binding(p/name) for name in ['PanoramaViewportActivity.java','fixture.html','AndroidManifest.xml','RUN.md','source-admission.json','build.sh','repack.sh','freeze.py']}
(p/'preparation-verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'prepared':True,'fixtureDex':result['fixtureDex'],'baselineFixture':result['baselineFixture'],'sourceApkExact':True,'zeroPermissions':True}))
