import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

FLATPAK = Path(__file__).resolve().parents[1]


class CiSourceTest(unittest.TestCase):
    def test_ci_builds_the_tested_git_revision_without_workspace_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'app.json'
            original = json.loads((FLATPAK / 'io.github.Giton22.Kyllene.Devel.json').read_text())
            manifest.write_text(json.dumps(original))
            (root / 'private-untracked.txt').write_text('must not become a source')
            commit = 'a' * 40
            result = subprocess.run([sys.executable, str(FLATPAK / 'prepare-ci.py'),
                                     str(manifest), '--repository', 'https://github.com/example/app.git',
                                     '--ref', 'refs/pull/42/merge', '--commit', commit],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            prepared = json.loads((root / 'ci.json').read_text())
            expected = json.loads(json.dumps(original))
            expected['modules'][0]['sources'][0].update(
                url='https://github.com/example/app.git', branch='refs/pull/42/merge', commit=commit)
            self.assertEqual(prepared, expected)
            self.assertEqual(json.loads(manifest.read_text()), original)
            self.assertNotIn('private-untracked', json.dumps(prepared))

    def test_unexpected_source_type_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'app.json'
            manifest.write_text(json.dumps({'modules': [{'sources': [{'type': 'dir'}]}]}))
            result = subprocess.run([sys.executable, str(FLATPAK / 'prepare-ci.py'),
                                     str(manifest), '--repository', 'https://github.com/example/app.git',
                                     '--ref', 'refs/heads/master', '--commit', 'a' * 40],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / 'ci.json').exists())
