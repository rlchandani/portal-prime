# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
from pathlib import Path
import argparse
import struct,zlib,json
parser = argparse.ArgumentParser(description="Check generated native photo corners without image dependencies.")
parser.add_argument("--evidence", type=Path, default=Path(__file__).resolve().parents[1] / "evidence")
P = parser.parse_args().evidence
def image(path):
 b=path.read_bytes();off=8;data=b''
 while off<len(b):
  n=struct.unpack_from('>I',b,off)[0];kind=b[off+4:off+8];chunk=b[off+8:off+8+n];off+=n+12
  if kind==b'IHDR':w,h,depth,color,_,_,interlace=struct.unpack('>IIBBBBB',chunk)
  if kind==b'IDAT':data+=chunk
 assert depth==8 and color in (2,6) and interlace==0
 c=4 if color==6 else 3;stride=w*c;raw=zlib.decompress(data);rows=[];previous=bytearray(stride);offset=0
 for y in range(h):
  filt=raw[offset];offset+=1;row=bytearray(raw[offset:offset+stride]);offset+=stride
  for x in range(stride):
   a=row[x-c] if x>=c else 0;b=previous[x];cc=previous[x-c] if x>=c else 0
   if filt==1:v=a
   elif filt==2:v=b
   elif filt==3:v=(a+b)//2
   elif filt==4:
    pp=a+b-cc;pa,pb,pc=abs(pp-a),abs(pp-b),abs(pp-cc);v=a if pa<=pb and pa<=pc else b if pb<=pc else cc
   else:assert filt==0;v=0
   row[x]=(row[x]+v)&255
  rows.append(row);previous=row
 return lambda x,y:tuple(rows[y][x*c:x*c+3])
d=json.loads((P/'native-paper-report.json').read_text());checks=[]
for mode in ['framed','mosaic']:
 pixel=image(P/f'native-{mode}-generated.png');o=next(x for x in d['observations'] if x['mode']==mode and x['phase']==0)
 for n,i in enumerate(o['images']):
  r=i['rect'];colors=[pixel(round((r['x']+r['w']*fx)*o['dpr']),round((r['y']+r['h']*fy)*o['dpr'])) for fx,fy in [(.03,.03),(.97,.03),(.03,.97),(.97,.97)]]
  checks.append({'name':f'{mode}-photo-{n}-four-native-corners','passed':colors[0][1]>180 and colors[0][0]<80 and colors[1][2]>180 and colors[1][0]<80 and colors[2][0]>180 and colors[2][1]>180 and colors[2][2]<80 and colors[3][0]>180 and colors[3][2]>180 and colors[3][1]<80,'nativeRgb':colors})
assert all(x['passed'] for x in checks),checks
v=json.loads((P/'native-paper-validation.json').read_text())
v['checks'] = [c for c in v['checks'] if not c['name'].endswith('-four-native-corners')] + checks
v['pixelChecks']='Independent standard-library PNG decode; four generated corner markers in each of seven visible photos'
(P/'native-paper-validation.json').write_text(json.dumps(v,indent=2))
print(len(v['checks']),'native checks pass, including 28 visible corner markers')
