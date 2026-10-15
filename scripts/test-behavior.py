#!/usr/bin/env python3
"""Run gameplay diagnostics in a new local-only fixture; never open production worlds."""
import argparse, datetime, hashlib, json, os, re, subprocess, uuid, zipfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--core',required=True,type=Path)
    parser.add_argument('--jdk',required=True,type=Path)
    parser.add_argument('--leaf-class-dir',type=Path,help='Optional isolated PR candidate classes')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    core=args.core.resolve()
    run=root/'.build-tools'/('behavior-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
    classes=run/'classes';classes.mkdir(parents=True)
    launcher=run/'launcher';launcher.mkdir()
    binary=lambda name: str(args.jdk.resolve()/'bin'/(name+('.exe' if os.name=='nt' else '')))
    subprocess.run([binary('javac'),'-proc:none','-cp',str(core),'-d',str(classes),
                    str(root/'tools/ProbeBehavior.java')],check=True)
    subprocess.run([binary('javac'),'-proc:none','-cp',str(core),'-d',str(launcher),
                    str(root/'tools/BehaviorLauncher.java')],check=True)
    plugins=run/'plugins';plugins.mkdir()
    with zipfile.ZipFile(plugins/'Probe.jar','w') as archive:
        archive.write(classes/'ProbeBehavior.class','ProbeBehavior.class')
        archive.writestr('plugin.yml','name: BehaviorProbe\nversion: "1"\nmain: ProbeBehavior\napi: ["1.0.4"]\nload: POSTWORLD\n')
    (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=19243\nlevel-name=test\nlevel-type=void\nxbox-auth=off\nauto-save=off\nenable-query=off\nallow-nether=off\nallow-the-end=off\n',encoding='utf-8')
    cp=[launcher]
    if args.leaf_class_dir:cp.append(args.leaf_class_dir.resolve())
    cp.append(core)
    with (run/'console.log').open('wb') as output:
        process=subprocess.Popen([binary('java'),'-Xmx512m','-Dfile.encoding=UTF-8','-Dterminal.jline=false',
            '-Dterminal.ansi=false','-cp',os.pathsep.join(map(str,cp)),'BehaviorLauncher'],cwd=run,
            stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT)
        try:process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.kill();process.wait();raise RuntimeError('Isolated test timed out')
    log=(run/'console.log').read_text(encoding='utf-8',errors='replace')
    leaf=re.search(r'LEAF_AUDIT States=(\d+) Mismatches=(\d+)',log)
    setters=re.search(r'LEAF_SETTER_AUDIT Cases=(\d+) Mismatches=(\d+)',log)
    ice=re.findall(r'ICE_BEHAVIOR ([^\r\n]+)',log)
    adjacent=re.findall(r'ADJACENCY_PASS ([^\r\n]+)',log)
    if not leaf or not setters or len(ice)!=3 or len(adjacent)!=15 or any('Correct=true' not in row for row in ice):
        raise RuntimeError(f'Incomplete or failed diagnostic; inspect {run / "console.log"}')
    report={'coreSha256':hashlib.sha256(core.read_bytes()).hexdigest(),
        'candidateClasses':str(args.leaf_class_dir) if args.leaf_class_dir else None,
        'leafStates':int(leaf[1]),'leafMismatches':int(leaf[2]),
        'leafSetterCases':int(setters[1]),'leafSetterMismatches':int(setters[2]),
        'iceBehavior':ice,'sameMaterialAdjacencyCases':len(adjacent),
        'mixedRules':re.findall(r'MIXED_[^\r\n]+',log),
        'persistentReproduction':re.findall(r'PERSISTENT_LEAF_REPRO[^\r\n]+',log),
        'scope':'Synthetic fixture. Does not verify converted original regions, client geometry or disconnects.',
        'log':str(run/'console.log')}
    (run/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 2 if report['leafMismatches'] or report['leafSetterMismatches'] else 0

if __name__=='__main__':raise SystemExit(main())
