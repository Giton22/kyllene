import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('repo_smoke.sh')


class RepoSmokeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('sh'), 'a POSIX shell is required')
    def test_unsupported_platform_stops_before_tools_or_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            uname = root / 'uname'
            uname.write_text('#!/bin/sh\necho Darwin\n')
            uname.chmod(0o755)
            result = subprocess.run([shutil.which('sh'), str(SCRIPT), '--fixture'], text=True,
                                    capture_output=True, env={**os.environ, 'PATH': str(root)}, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('This installed-Flatpak test requires Linux', result.stderr)
            self.assertEqual(list(root.iterdir()), [uname])

    @unittest.skipUnless(sys.platform == 'linux' and all(shutil.which(tool) for tool in
                        ('flatpak', 'ostree', 'gpg', 'gpgconf', 'timeout')), 'Linux Flatpak/OSTree/GPG tools required')
    def test_two_routes_update_and_reject_wrong_key_in_isolated_installations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Temp roots must be gone afterwards, including signing agents and HTTP-server files.
            result = subprocess.run(['timeout', '80', shutil.which('sh'), str(SCRIPT), '--fixture'], text=True, capture_output=True,
                                    env={**os.environ, 'TMPDIR': directory}, timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for route in ('bundle', 'ref'):
                self.assertIn(route + ': updated from release 1 to release 2', result.stdout)
                self.assertIn(route + ': refused wrong-key update and retained release 2', result.stdout)
            self.assertIn('both routes passed with two prepared releases', result.stdout)
            self.assertEqual(list(root.iterdir()), [])

    @unittest.skipUnless(sys.platform == 'linux' and all(shutil.which(tool) for tool in
                        ('flatpak', 'ostree', 'gpg', 'gpgconf', 'timeout')), 'Linux Flatpak/OSTree/GPG tools required')
    def test_bundle_mode_publishes_and_updates_to_the_given_candidate_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_dir = root / 'app'
            (app_dir / 'files').mkdir(parents=True)
            (app_dir / 'export').mkdir()
            app = 'app.kyllene.RepositoryTest'
            (app_dir / 'metadata').write_text(
                f'[Application]\nname={app}\nruntime=org.freedesktop.Platform/x86_64/26.08\n')
            (app_dir / 'files/payload').write_text('candidate payload\n')
            repo = root / 'repo'
            bundle = root / 'candidate.flatpak'
            prod_gnupg = root / 'prod-gnupg'
            prod_gnupg.mkdir(mode=0o700)
            prod_env = {**os.environ, 'GNUPGHOME': str(prod_gnupg)}
            try:
                subprocess.run(['gpg', '--batch', '--quiet', '--passphrase', '', '--quick-gen-key',
                                'Candidate release key', 'rsa2048', 'sign', 'never'],
                               check=True, capture_output=True, env=prod_env)
                keys = subprocess.run(['gpg', '--list-keys', '--with-colons'],
                                      check=True, capture_output=True, text=True, env=prod_env).stdout
                prod_key = next(line.split(':')[9] for line in keys.splitlines() if line.startswith('fpr:'))
                prod_pub = root / 'prod-key.gpg'
                prod_pub.write_bytes(subprocess.run(['gpg', '--export', prod_key],
                                                    check=True, capture_output=True, env=prod_env).stdout)
                subprocess.run(['flatpak', 'build-export', '--arch=x86_64', '--timestamp=2025-01-01T00:00:00Z',
                                str(repo), str(app_dir), 'stable'],
                               check=True, capture_output=True)
                subprocess.run(['flatpak', 'build-sign', '--arch=x86_64', '--gpg-sign=' + prod_key,
                                '--gpg-homedir=' + str(prod_gnupg), str(repo), app, 'stable'],
                               check=True, capture_output=True)
                subprocess.run(['flatpak', 'build-bundle', '--repo-url=https://flatpak.example.invalid/repo/',
                                '--gpg-keys=' + str(prod_pub), str(repo), str(bundle), app, 'stable'],
                               check=True, capture_output=True)
            finally:
                subprocess.run(['gpgconf', '--homedir', str(prod_gnupg), '--kill', 'all'], capture_output=True)
            tmp = root / 'tmp'
            tmp.mkdir()
            result = subprocess.run(
                ['timeout', '80', shutil.which('sh'), str(SCRIPT), '--bundle', str(bundle), str(repo), app, 'stable'],
                text=True, capture_output=True, env={**os.environ, 'TMPDIR': str(tmp)}, timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for route in ('bundle', 'ref'):
                self.assertIn(route + ': updated from release 1 to release 2', result.stdout)
                self.assertIn(route + ': refused wrong-key update and retained release 2', result.stdout)
            self.assertEqual(list(tmp.iterdir()), [])
