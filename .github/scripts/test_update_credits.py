"""update-credits.py on copies of the app's credits list and the README."""

import importlib.util
import io
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("update_credits", HERE / "update-credits.py")
credits = importlib.util.module_from_spec(spec)
spec.loader.exec_module(credits)


class UpdateCreditsTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        credits.KOTLIN = self.dir / "SupportScreen.kt"
        credits.README = self.dir / "README.md"
        shutil.copy(ROOT / "shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/settings/SupportScreen.kt", credits.KOTLIN)
        shutil.copy(ROOT / "README.md", credits.README)

    def run_with(self, contributors, sponsors):
        files = []
        for name, logins in (("contributors.txt", contributors), ("sponsors.txt", sponsors)):
            path = self.dir / name
            path.write_text("".join(f"{login}\n" for login in logins), encoding="utf-8")
            files.append(path)
        with redirect_stdout(io.StringIO()):
            credits.main(*files)
        return credits.KOTLIN.read_text(encoding="utf-8"), credits.README.read_text(encoding="utf-8")

    def test_no_one_new_changes_nothing(self):
        kotlin, readme = credits.KOTLIN.read_text(encoding="utf-8"), credits.README.read_text(encoding="utf-8")
        self.assertEqual((kotlin, readme), self.run_with([], []))

    def test_a_new_sponsor_is_added_once(self):
        kotlin, readme = self.run_with([], ["new-sponsor"])
        self.assertIn('"new-sponsor"', kotlin)
        self.assertIn("[new-sponsor](https://github.com/new-sponsor)", readme)
        self.assertEqual((kotlin, readme), self.run_with([], ["new-sponsor"]))

    def test_a_sponsor_whose_account_is_gone_is_not_credited(self):
        # The GraphQL sponsorEntity is null for a deleted account, which jq prints as "null".
        kotlin, readme = self.run_with(["null"], ["null"])
        self.assertNotIn('"null"', kotlin)
        self.assertNotIn("github.com/null", readme)


if __name__ == "__main__":
    unittest.main()
