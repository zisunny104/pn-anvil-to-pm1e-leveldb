#!/usr/bin/env python3
"""Prepare official runtimes and supervise explicit fallback without mixing output."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE_COMMIT = 'd2a0848f16efe1e3c53fc411c61af62d12a8c683'
SOURCE_REPO = 'https://github.com/PowerNukkit/PowerNukkit.git'
TARGET_REPO = 'PetteriM1/NukkitPetteriM1Edition'
VERIFIED_TAG = '4511'
VERIFIED_SHA = '67cec0084ed161bb558ab2c103dd15b0939a258f39a0ff5cb98e5de896a86acd'

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.pending')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        'User-Agent': 'pn-anvil-to-pm1e-leveldb', 'Accept': 'application/vnd.github+json'
    }), timeout=60)

def jar_check(path, required):
    with zipfile.ZipFile(path) as archive:
        for name in required:
            if name not in archive.namelist():
                raise RuntimeError(f'核心缺少必要項目：{name}')
        bad = archive.testzip()
        if bad:
            raise RuntimeError(f'JAR CRC 檢查失敗：{bad}')

def target(profile):
    endpoint = 'latest' if profile == 'latest' else f'tags/{VERIFIED_TAG}'
    with request(f'https://api.github.com/repos/{TARGET_REPO}/releases/{endpoint}') as response:
        release = json.load(response)
    assets = [a for a in release['assets'] if a['name'] == 'Nukkit-PM1E.jar']
    if len(assets) != 1:
        raise RuntimeError('找不到唯一的官方 Nukkit-PM1E.jar')
    asset = assets[0]
    tag = release['tag_name']
    official_digest = asset.get('digest') or ''
    expected = VERIFIED_SHA if profile == 'verified' else (
        official_digest[len('sha256:'):] if official_digest.startswith('sha256:') else None)
    if not expected or len(expected) != 64:
        raise RuntimeError('官方發布檔未提供 SHA-256，停止自動下載；不略過檢查')
    cache = ROOT / 'runtime' / 'downloads' / expected / 'target.jar'
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists() or sha(cache) != expected:
        temporary = cache.with_suffix('.part')
        print(f'下載官方 PM1E：{tag}', flush=True)
        with request(asset['browser_download_url']) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        if sha(temporary) != expected:
            temporary.unlink()
            raise RuntimeError('PM1E SHA-256 不符')
        jar_check(temporary, ['cn/nukkit/level/format/leveldb/BlockStateMapping.class'])
        temporary.replace(cache)
    jar_check(cache, ['cn/nukkit/level/format/leveldb/BlockStateMapping.class'])
    return cache, {'repository': TARGET_REPO, 'tag': tag, 'sha256': expected,
        'url': asset['browser_download_url'], 'historically_validated_binary': expected == VERIFIED_SHA}

def source():
    destination = ROOT / 'runtime' / 'source-build'
    cache = ROOT / 'runtime' / 'source-cache' / SOURCE_COMMIT / 'source.jar'
    provenance = cache.with_suffix('.json')
    if cache.exists() and provenance.exists():
        record = json.loads(provenance.read_text(encoding='utf-8'))
        if record.get('commit') == SOURCE_COMMIT and sha(cache) == record.get('sha256'):
            jar_check(cache, ['canonical_block_states.nbt', 'cn/nukkit/blockstate/BlockState.class'])
            return cache, record
        raise RuntimeError('來源核心快取與紀錄不符，請檢查 runtime/source-cache')
    print('來源已驗證 commit 沒有對應的官方 Release JAR，從官方原始碼編譯。', flush=True)
    if not shutil.which('git') or not shutil.which('mvn'):
        raise RuntimeError('來源核心編譯需要 git 與 Maven；請先安裝，不改用不同的來源版本')
    destination.mkdir(parents=True, exist_ok=True)
    if not (destination / '.git').exists():
        subprocess.run(['git', 'init', str(destination)], check=True)
        subprocess.run(['git', '-C', str(destination), 'remote', 'add', 'origin', SOURCE_REPO], check=True)
    remote = subprocess.check_output(['git', '-C', str(destination), 'remote', 'get-url', 'origin'], text=True).strip()
    if remote != SOURCE_REPO:
        raise RuntimeError('來源 checkout 的 origin 不是指定官方倉庫')
    subprocess.run(['git', '-C', str(destination), 'fetch', '--depth=1', 'origin', SOURCE_COMMIT], check=True)
    subprocess.run(['git', '-C', str(destination), 'checkout', '--detach', SOURCE_COMMIT], check=True)
    commit = subprocess.check_output(['git', '-C', str(destination), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != SOURCE_COMMIT:
        raise RuntimeError('來源 commit 不符')
    if subprocess.check_output(['git', '-C', str(destination), 'status', '--porcelain'], text=True).strip():
        raise RuntimeError('來源 checkout 有修改，停止編譯')
    environment = os.environ.copy()
    if environment.get('SOURCE_JAVA_HOME'):
        environment['JAVA_HOME'] = environment['SOURCE_JAVA_HOME']
        environment['PATH'] = str(Path(environment['JAVA_HOME']) / 'bin') + os.pathsep + environment['PATH']
    java_version = subprocess.run(['java', '-version'], env=environment, capture_output=True, text=True, check=True)
    version_text = java_version.stderr + java_version.stdout
    if '"1.8.' not in version_text:
        raise RuntimeError('舊 PowerNukkit 原始碼需 JDK 8 編譯。請以 SOURCE_JAVA_HOME 指定 JDK 8；轉換仍使用 JDK 21')
    subprocess.run(['mvn', '-B', '-DskipTests', 'clean', 'package'], cwd=destination, env=environment, check=True)
    artifacts = list((destination / 'target').glob('*-shaded.jar'))
    if len(artifacts) != 1:
        raise RuntimeError('來源編譯沒有產生唯一的 shaded JAR')
    jar_check(artifacts[0], ['canonical_block_states.nbt', 'cn/nukkit/blockstate/BlockState.class'])
    cache.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifacts[0], cache)
    record = {'repository': SOURCE_REPO, 'commit': commit, 'sha256': sha(cache),
        'built_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'java': version_text.strip(), 'historically_validated_commit': True,
        'historically_validated_binary': False,
        'note': '從已驗證 commit 重新編譯；未宣稱與 VPS 使用的原始 JAR 位元組相同'}
    atomic_json(provenance, record)
    return cache, record

def prepare(profile, resume=False):
    active_path = ROOT / 'runtime' / 'active.json'
    batch = ROOT / 'runtime' / 'batch'
    batch.mkdir(parents=True, exist_ok=True)
    if resume:
        record = json.loads(active_path.read_text(encoding='utf-8'))
        for role in ('source', 'target'):
            path = batch / f'{role}.jar'
            if not path.exists() or sha(path) != record[role]['sha256']:
                raise RuntimeError(f'續跑核心 {role} 與原紀錄不符')
        print(f'續跑固定使用 PM1E {record["target"]["tag"]}，不更新途中核心。', flush=True)
        return record
    source_path, source_record = source()
    target_path, target_record = target(profile)
    # Official release is a shaded JAR; do not reuse libs from another core version.
    libs = batch / 'libs'
    if libs.exists() and any(libs.iterdir()):
        raise RuntimeError('runtime/batch/libs 非空，可能混入舊依賴；請使用乾淨的工具工作目錄')
    shutil.copy2(source_path, batch / 'source.jar')
    shutil.copy2(target_path, batch / 'target.jar')
    record = {'profile': profile, 'source': source_record, 'target': target_record,
        'prepared_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    atomic_json(active_path, record)
    atomic_json(ROOT / 'reports' / 'runtime.json', record)
    print(f'RUNTIME_PASS PM1E={target_record["tag"]} SHA256={target_record["sha256"]}', flush=True)
    return record

def preserve_attempt():
    destination = ROOT / 'backups' / ('failed-attempt-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    destination.mkdir(parents=True)
    for name in ('after', 'reports', 'intermediate'):
        path = ROOT / name
        if path.exists():
            shutil.move(str(path), str(destination / name))
        path.mkdir()
    print(f'失敗輸出與日誌已保留：{destination}', flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verified', action='store_true', help='明確指定已驗證 PM1E 4511')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('worlds', nargs='*')
    args = parser.parse_args()
    os.chdir(ROOT)
    if not args.prepare_only and not args.worlds:
        parser.error('請提供 before/ 下的世界名稱')
    for world in args.worlds:
        if not world or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in world):
            parser.error('世界名稱只接受英數字、底線及連字號')
        if not (ROOT / 'before' / world / 'region').is_dir() or not (ROOT / 'before' / world / 'level.dat').is_file():
            parser.error(f'找不到來源世界：{world}')
    if os.name != 'posix':
        parser.error('自動轉換入口目前需要 Linux；Windows 請使用 Linux 環境')
    import fcntl
    lock_path = ROOT / 'runtime' / 'entry.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        resume = (ROOT / 'reports' / 'runtime-fingerprint').exists()
        profile = 'verified' if args.verified else 'latest'
        record = None
        try:
            if resume and args.verified:
                active = json.loads((ROOT / 'runtime' / 'active.json').read_text(encoding='utf-8'))
                if active['target']['sha256'] != VERIFIED_SHA:
                    raise RuntimeError('續跑不可換核心。請以另一個乾淨工作目錄執行 --verified')
            record = prepare(profile, resume)
            if args.prepare_only:
                return 0
            result = subprocess.run(['bash', 'scripts/convert.sh', *args.worlds])
            if result.returncode == 0:
                return 0
            raise RuntimeError(f'轉換或驗證失敗，退出碼 {result.returncode}')
        except Exception as error:
            print(f'執行停止：{error}', file=sys.stderr)
            if args.verified or (record and record['target']['sha256'] == VERIFIED_SHA):
                return 1
            if not sys.stdin.isatty():
                print('非互動執行不會自行回退；請檢查日誌後明確指定 --verified。', file=sys.stderr)
                return 1
            answer = input('是否改用已驗證 PM1E 4511，保留失敗資料並重新轉換？[y/N] ')
            if answer.strip().lower() not in ('y', 'yes'):
                return 1
            preserve_attempt()
            prepare('verified')
            if args.prepare_only:
                return 0
            return subprocess.run(['bash', 'scripts/convert.sh', *args.worlds]).returncode

if __name__ == '__main__':
    try:
        sys.exit(main())
    except (Exception, KeyboardInterrupt) as error:
        print(f'執行停止：{error}', file=sys.stderr)
        sys.exit(1)
