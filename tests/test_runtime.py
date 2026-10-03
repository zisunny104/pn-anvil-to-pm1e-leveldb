import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('runtime', Path(__file__).parents[1] / 'scripts/runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

def jar_bytes():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('cn/nukkit/level/format/leveldb/BlockStateMapping.class', b'test')
    return buffer.getvalue()

class RuntimeChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.patcher = patch.object(runtime, 'ROOT', self.root)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def release(self, digest):
        return {'tag_name': 'test-build', 'assets': [{'name': 'Nukkit-PM1E.jar',
            'digest': digest, 'browser_download_url': 'https://github.com/test/core.jar'}]}

    def test_latest_requires_official_digest(self):
        with patch.object(runtime, 'request', return_value=io.BytesIO(json.dumps(self.release(None)).encode())):
            with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
                runtime.target('latest')

    def test_wrong_download_never_installed(self):
        release = self.release('sha256:' + 'a' * 64)
        with patch.object(runtime, 'request', side_effect=[io.BytesIO(json.dumps(release).encode()), io.BytesIO(jar_bytes())]):
            with self.assertRaisesRegex(RuntimeError, 'SHA-256 不符'):
                runtime.target('latest')
        self.assertEqual(list(self.root.rglob('target.jar')), [])

    def test_valid_download_checked_and_recorded(self):
        payload = jar_bytes()
        expected = hashlib.sha256(payload).hexdigest()
        with patch.object(runtime, 'request', side_effect=[
                io.BytesIO(json.dumps(self.release('sha256:' + expected)).encode()), io.BytesIO(payload)]):
            path, record = runtime.target('latest')
        self.assertEqual(path.read_bytes(), payload)
        self.assertEqual(record['sha256'], expected)
        self.assertFalse(record['historically_validated_binary'])

    def test_fallback_preserves_outputs_and_reports(self):
        for name in ('after', 'reports', 'intermediate'):
            (self.root / name).mkdir()
            (self.root / name / 'evidence').write_text(name)
        runtime.preserve_attempt()
        backup = next((self.root / 'backups').iterdir())
        for name in ('after', 'reports', 'intermediate'):
            self.assertEqual((backup / name / 'evidence').read_text(), name)
            self.assertEqual(list((self.root / name).iterdir()), [])

    def test_resume_rejects_changed_binary(self):
        (self.root / 'runtime' / 'batch').mkdir(parents=True)
        runtime.atomic_json(self.root / 'runtime' / 'active.json',
            {'source': {'sha256': 'a' * 64}, 'target': {'sha256': 'b' * 64}})
        (self.root / 'runtime' / 'batch' / 'source.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(RuntimeError, '續跑核心'):
            runtime.prepare('latest', resume=True)

if __name__ == '__main__':
    unittest.main()
