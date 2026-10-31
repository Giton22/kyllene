import os
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

from PIL import Image
import installed_smoke as smoke


class InstalledSmokeTest(unittest.TestCase):
    def test_failed_private_capture_never_falls_back_to_desktop_screenshot(self):
        with patch.dict(os.environ, DISPLAY=':9876'), \
             patch.object(Image.core, 'grabscreen_x11', side_effect=OSError('private display unavailable')), \
             patch('PIL.ImageGrab.shutil.which', return_value='/usr/bin/spectacle'), \
             patch('PIL.ImageGrab.subprocess.call') as fallback:
            with self.assertRaisesRegex(OSError, 'private display unavailable'):
                smoke.pixels((25, 195, 125))
            fallback.assert_not_called()

    def test_gateway_setup_failure_uninstalls_only_the_owned_commit(self):
        commands = []
        def run(*args):
            commands.append(args)
            return 'owned-commit'
        with patch.object(smoke, 'run', side_effect=run), \
             patch.object(smoke, 'server', side_effect=OSError('fixture bind failed')):
            with self.assertRaisesRegex(OSError, 'fixture bind failed'):
                smoke.main(Path('fixture.flatpak'))
        self.assertIn(('flatpak', 'uninstall', '--user', '--noninteractive', '-y', smoke.APP), commands)

    def test_process_cleanup_failure_still_closes_gateway_and_owned_installation(self):
        commands = []
        def run(*args):
            commands.append(args)
            if args[:2] == ('flatpak', 'ps'):
                raise OSError('process listing failed')
            return 'owned-commit'
        gateway = Mock()
        app = Mock(pid=12345)
        app.poll.return_value = None
        with patch.object(smoke.subprocess, 'Popen', return_value=app), \
             patch.object(smoke, 'run', side_effect=run), \
             patch.object(smoke, 'server', return_value=gateway), \
             patch.object(smoke, 'until', side_effect=RuntimeError('UI check failed')):
            with self.assertRaisesRegex(OSError, 'process listing failed') as failure:
                smoke.main(Path('fixture.flatpak'))
        self.assertIsInstance(failure.exception.__context__, RuntimeError)
        app.terminate.assert_called_once()
        app.wait.assert_called_once_with(timeout=15)
        gateway.shutdown.assert_called_once()
        gateway.server_close.assert_called_once()
        self.assertIn(('flatpak', 'uninstall', '--user', '--noninteractive', '-y', smoke.APP), commands)

    def test_changed_ref_is_preserved_when_cleanup_cannot_prove_ownership(self):
        def run(*args):
            if args[:2] == ('flatpak', 'uninstall'):
                self.fail('must preserve a ref that changed during the test')
            return 'other-commit'
        with patch.object(smoke, 'run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'ref changed'):
                smoke.uninstall('owned-commit')

    def test_existing_installation_is_never_replaced(self):
        commands = []
        def run(*args):
            commands.append(args)
            return smoke.APP
        with patch.object(smoke, 'run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'Refusing to replace'):
                smoke.main(Path('fixture.flatpak'))
        self.assertEqual(commands, [('flatpak', 'list', '--user', '--app', '--columns=application')])
