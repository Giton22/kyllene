import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

STAGE = Path(__file__).resolve().parents[1] / 'stage.py'


class StageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'space café'
        self.image = self.root / 'image'
        self.prefix = self.root / 'installed'
        app = self.image / 'lib/app'
        app.mkdir(parents=True)
        (self.image / 'bin').mkdir()
        native_launcher = self.image / 'bin/Kyllene'
        native_launcher.write_text('#!' + sys.executable + '\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
        native_launcher.chmod(0o755)
        (app / 'Kyllene.cfg').write_text('''[Application]
app.classpath=$APPDIR/main.jar
app.mainclass=fixture.Main

[JavaOptions]
java-options=-Dherald.version=1.2.3
java-options=-Dcompose.application.resources.dir=$APPDIR/resources
java-options=-Dliteral=space;$(touch INJECTED) café
''')
        (app / 'main.jar').touch()
        native = self.image / 'lib/runtime/lib'
        (native / 'locales').mkdir(parents=True)
        for name in ['libcef.so', 'libjcef.so', 'jcef_helper', 'icudtl.dat',
                     'resources.pak', 'chrome_100_percent.pak', 'chrome_200_percent.pak',
                     'locales/en-US.pak']:
            (native / name).touch()

    def stage(self):
        return subprocess.run([sys.executable, str(STAGE), str(self.image),
                               str(self.prefix)], capture_output=True, text=True)

    def test_launcher_preserves_literal_arguments(self):
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = ['--argument', 'two words', 'café', '$(touch INJECTED)']
        run = subprocess.run([str(self.prefix / 'bin/kyllene'), *args],
                             cwd=self.root, capture_output=True, text=True, check=True)
        actual = json.loads(run.stdout)
        self.assertEqual(actual, args)
        self.assertFalse((self.root / 'INJECTED').exists())

    def test_qualified_native_launcher_is_preserved(self):
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        launcher = self.prefix / 'bin/kyllene'
        self.assertTrue(launcher.is_symlink())
        run = subprocess.run([str(launcher), 'space café', '$(literal)'],
                             capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(run.stdout), ['space café', '$(literal)'])

    def test_missing_chromium_payload_refuses_staging(self):
        (self.image / 'lib/runtime/lib/icudtl.dat').unlink()
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('icudtl.dat', result.stderr)
        self.assertFalse((self.prefix / 'bin/kyllene').exists())

    def test_missing_main_class_refuses_staging(self):
        config = self.image / 'lib/app/Kyllene.cfg'
        config.write_text('[Application]\napp.classpath=$APPDIR/main.jar\n')
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('app.mainclass', result.stderr)


if __name__ == '__main__':
    unittest.main()
