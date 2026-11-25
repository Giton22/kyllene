import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

FLATPAK = Path(__file__).resolve().parents[1]
SCRIPT = FLATPAK / 'download-sources.py'
WORKFLOWS = FLATPAK.parents[1] / '.github/workflows'

# Stands in for flatpak-builder: fails while the counter file holds a number above zero, counting it down, and logs
# its arguments.
FAKE_BUILDER = '''#!/bin/sh
echo "$@" >> "$FAKE_DIR/calls"
left=$(cat "$FAKE_DIR/failures")
if [ "$left" -gt 0 ]; then
  echo $((left - 1)) > "$FAKE_DIR/failures"
  echo "The requested URL returned error: 429" >&2
  exit 1
fi
'''


class DownloadSourcesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / 'bin').mkdir()
        builder = self.root / 'bin' / 'flatpak-builder'
        builder.write_text(FAKE_BUILDER)
        builder.chmod(0o755)
        (self.root / 'sources.json').write_text(json.dumps([
            {'type': 'file', 'url': 'https://example.invalid/a.pom', 'sha512': 'a' * 128, 'dest-filename': 'a.pom'},
        ]))
        self.manifest = self.root / 'app.json'
        self.manifest.write_text(json.dumps({'modules': [{'name': 'app', 'sources': [
            {'type': 'git', 'url': 'file:///workspace', 'commit': 'c' * 40},
            {'type': 'archive', 'url': 'https://example.invalid/jdk.tar.gz', 'sha256': 'b' * 64},
            'sources.json',
        ]}]}))
        self.downloads = self.root / 'state' / 'downloads'

    def tearDown(self):
        self.directory.cleanup()

    def run_script(self, failures):
        (self.root / 'failures').write_text(str(failures))
        env = dict(os.environ, FAKE_DIR=str(self.root), PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'])
        return subprocess.run([sys.executable, str(SCRIPT), 'build', str(self.manifest),
                               '--state-dir', str(self.root / 'state'), '--attempts', '3', '--delay', '0'],
                              env=env, capture_output=True, text=True)

    def calls(self):
        return (self.root / 'calls').read_text().splitlines()

    def test_a_rate_limited_download_is_retried(self):
        result = self.run_script(failures=2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.calls()), 3)
        self.assertIn('attempt 2 of 3', result.stdout)
        self.assertIn('--download-only', self.calls()[0].split())

    def test_it_gives_up_after_the_last_attempt(self):
        result = self.run_script(failures=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls()), 3)

    def test_cached_downloads_the_manifest_no_longer_lists_are_removed(self):
        for name in ('a' * 128, 'b' * 64, 'd' * 64):
            (self.downloads / name).mkdir(parents=True)
            (self.downloads / name / 'file').write_text('x')
        result = self.run_script(failures=0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in self.downloads.iterdir()), ['a' * 128, 'b' * 64])

    def test_the_package_builds_use_the_downloaded_sources(self):
        for name in ('linux.yml', 'release.yml'):
            workflow = (WORKFLOWS / name).read_text()
            self.assertIn('packaging/flatpak/download-sources.py', workflow, name)
            self.assertIn('path: .flatpak-builder/downloads', workflow, name)
            build = [line for line in workflow.splitlines() if 'flatpak-builder --user' in line and '--repo=' in line]
            self.assertEqual(len(build), 1, name)
            self.assertIn('--disable-download', build[0], name)


if __name__ == '__main__':
    unittest.main()
