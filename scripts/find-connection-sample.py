#!/usr/bin/env python3
"""Locate adjacent original blocks; export one diagnostic region without changing worlds."""
import argparse, gzip, json, shutil, subprocess, uuid
from pathlib import Path

IDS={101:'iron-bars',102:'glass-pane',160:'stained-glass-pane',85:'wood-fence',113:'nether-fence'}

def pair(row):
    for storage in row['storages']:
        if storage['layer']!=0:continue
        palette=storage['palette']; indices=storage['indices']
        ids=[int(item['legacyId']) for item in palette]
        if not any(block in IDS for block in ids):continue
        if len(indices)!=4096:raise RuntimeError('Unexpected storage length')
        for index,slot in enumerate(indices):
            block=ids[slot]
            if block not in IDS:continue
            x,z,y=index>>8,(index>>4)&15,index&15
            neighbors=[]
            if x<15:neighbors.append(index+256)
            if z<15:neighbors.append(index+16)
            for neighbor in neighbors:
                other=indices[neighbor]
                if ids[other]!=block:continue
                def point(i):return [row['chunkX']*16+(i>>8),storage['y']*16+(i&15),row['chunkZ']*16+((i>>4)&15)]
                return {'chunk':[row['chunkX'],row['chunkZ']],'kind':IDS[block],
                    'positions':[point(index),point(neighbor)],'sourceStates':[palette[slot],palette[other]],
                    'expectation':'Same-material horizontally adjacent pair; verify client geometry separately.'}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--work',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--world',action='append')
    args=parser.parse_args();root=args.work.resolve()
    jar=root/'runtime/batch/source.jar'
    if not jar.is_file() or not (root/'tools/CompactExport.class').is_file():
        raise RuntimeError('Use the existing verified converter workspace with compiled CompactExport and source.jar')
    worlds=args.world or [p.name for p in sorted((root/'before').iterdir()) if p.is_dir()]
    output=root/'reports'/('connection-sample-'+uuid.uuid4().hex[:12]);output.mkdir(parents=True)
    for world in worlds:
        folder=(root/'before'/world).resolve()
        if folder.parent!=(root/'before').resolve():raise RuntimeError('Invalid world name')
        for region in sorted((folder/'region').glob('*.mca')):
            exported=output/'source.gz'
            print('Inspect:',world,region.name,flush=True)
            with (output/'export.log').open('a',encoding='utf-8') as log:
                subprocess.run(['java','-Xmx192m','-cp',str(root/'tools')+':'+str(jar),
                    'CompactExport',str(region),str(exported)],cwd=root,stdout=log,stderr=log,check=True)
            found=None
            with gzip.open(exported,'rt',encoding='utf-8') as rows:
                for line in rows:
                    found=pair(json.loads(line))
                    if found:break
            if found:
                found.update({'world':world,'sourceRegion':str(region)})
                (output/'sample.json').write_text(json.dumps(found,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
                shutil.copy2(region,output/region.name)
                print(json.dumps(found,ensure_ascii=False,indent=2));print('Sample:',output);return
            exported.unlink()
    raise RuntimeError('No same-material adjacent pair found; partial diagnostics retained')

if __name__=='__main__':main()
