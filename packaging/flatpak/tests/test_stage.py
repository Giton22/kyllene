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
        java = self.root / 'jdk/bin/java'
        java.parent.mkdir(parents=True)
        java.write_text('#!' + sys.executable + '\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
        java.chmod(0o755)
        (app / 'main.jar').touch()
        native = self.image / 'lib/runtime/lib'
        (native / 'locales').mkdir(parents=True)
        for name in ['libcef.so', 'libjcef.so', 'jcef_helper', 'icudtl.dat',
                     'resources.pak', 'chrome_100_percent.pak', 'chrome_200_percent.pak',
                     'locales/en-US.pak']:
            (native / name).touch()

    def stage(self):
        return subprocess.run([sys.executable, str(STAGE), str(self.image),
                               str(self.prefix), str(self.root / 'jdk/bin/java')], capture_output=True, text=True)

    def test_launcher_preserves_literal_arguments(self):
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = ['--argument', 'two words', 'café', '$(touch INJECTED)']
        run = subprocess.run([str(self.prefix / 'bin/kyllene'), *args],
                             cwd=self.root, capture_output=True, text=True, check=True)
        actual = json.loads(run.stdout)
        self.assertEqual(actual[-len(args):], args)
        self.assertFalse((self.root / 'INJECTED').exists())

    def test_matching_java_uses_generated_configuration(self):
        config = self.image / 'lib/app/Kyllene.cfg'
        config.write_text(config.read_text() + '\n[ArgOptions]\narguments=default café\n')
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        launcher = self.prefix / 'bin/kyllene'
        self.assertFalse(launcher.is_symlink())
        run = subprocess.run([str(launcher)], cwd=self.root,
                             capture_output=True, text=True, check=True)
        app = self.prefix / 'lib/kyllene/lib/app'
        actual = json.loads(run.stdout)
        self.assertEqual(actual, [
            '-classpath', str(app / 'main.jar'), '-Dherald.version=1.2.3',
            '-Dcompose.application.resources.dir=' + str(app / 'resources'),
            '-Dliteral=space;$(touch INJECTED) café',
            '-Djpackage.app-path=' + str(self.prefix / 'lib/kyllene/bin/Kyllene'),
            'fixture.Main', 'default café'])
        self.assertFalse((self.root / 'INJECTED').exists())
        self.assertEqual((app / 'Kyllene.cfg').read_bytes(), config.read_bytes())
        self.assertEqual((self.prefix / 'lib/kyllene/bin/Kyllene').read_bytes(),
                         (self.image / 'bin/Kyllene').read_bytes())

    def test_classpath_larger_than_native_pipe_is_preserved(self):
        config = self.image / 'lib/app/Kyllene.cfg'
        entries = ['$APPDIR/' + ('x' * 100) + str(i) + '.jar' for i in range(160)]
        config.write_text('[Application]\napp.mainclass=fixture.Main\n' +
                          ''.join('app.classpath=' + x + '\n' for x in entries))
        self.assertEqual(self.stage().returncode, 0)
        run = subprocess.run([str(self.prefix / 'bin/kyllene')],
                             capture_output=True, text=True, check=True)
        expected = ':'.join(x.replace('$APPDIR', str(self.prefix / 'lib/kyllene/lib/app'))
                            for x in entries)
        self.assertEqual(json.loads(run.stdout)[:2], ['-classpath', expected])

    def test_missing_chromium_payload_refuses_staging(self):
        (self.image / 'lib/runtime/lib/icudtl.dat').unlink()
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('icudtl.dat', result.stderr)
        self.assertFalse((self.prefix / 'bin/kyllene').exists())

    def test_restored_chromium_helper_can_be_executed(self):
        helper = self.image / 'lib/runtime/lib/jcef_helper'
        helper.write_text('#!' + sys.executable + '\nprint("helper-ready")\n')
        helper.chmod(0o644)
        result = self.stage()
        self.assertEqual(result.returncode, 0, result.stderr)
        run = subprocess.run([str(self.prefix / 'lib/kyllene/lib/runtime/lib/jcef_helper')],
                             check=True, capture_output=True, text=True)
        self.assertEqual(run.stdout.strip(), 'helper-ready')

    def test_missing_main_class_refuses_staging(self):
        config = self.image / 'lib/app/Kyllene.cfg'
        config.write_text('[Application]\napp.classpath=$APPDIR/main.jar\n')
        result = self.stage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('app.mainclass', result.stderr)


if __name__ == '__main__':
    unittest.main()
