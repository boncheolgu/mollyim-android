import tempfile
import unittest
from pathlib import Path
from xml.sax.saxutils import escape

from pin_race_proof import CONTROL, MARKER, METHOD, check_baseline


class PinRaceProofTest(unittest.TestCase):
    def report(self, case, exit_code=1, control=""):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            path.write_text(f'<testsuite><testcase name="{METHOD}">{case}</testcase><testcase name="{CONTROL}">{control}</testcase></testsuite>')
            return check_baseline(path, exit_code)

    def test_only_the_exact_functional_failure_counts_as_reproduction(self):
        message = f"{MARKER} expected:<1> but was:<0>"
        self.assertEqual(self.report(f'<failure message="{escape(message)}"/>'), message)

    def test_unexpected_pass_or_skip_is_not_reproduction(self):
        for case, code in [("", 0), ("<skipped/>", 1)]:
            with self.subTest(case=case), self.assertRaises(AssertionError):
                self.report(case, code)

    def test_fixture_errors_and_other_assertions_do_not_count(self):
        for case in ['<error message="missing SDK"/>', '<failure message="theme unavailable"/>']:
            with self.subTest(case=case), self.assertRaises(AssertionError):
                self.report(case)

    def test_failing_or_skipped_control_is_not_proof(self):
        message = f"{MARKER} expected:<1> but was:<0>"
        for control in ['<failure message="ordinary resume failed"/>', '<skipped/>']:
            with self.subTest(control=control), self.assertRaises(AssertionError):
                self.report(f'<failure message="{escape(message)}"/>', control=control)


if __name__ == "__main__":
    unittest.main()
