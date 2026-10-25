import datetime as dt
import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("evaluate-image-scan.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
class ScanPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policy = {"reviewedOn":"2026-10-24", "expiresOn":"2026-11-23", "decisions":[{
            "id":"CVE-2026-47884", "package":"org.springframework:spring-webmvc", "version":"6.2.19", "status":"not_affected"}]}
        self.v = {"VulnerabilityID":"CVE-2026-47884", "PkgName":"org.springframework:spring-webmvc", "InstalledVersion":"6.2.19", "Severity":"CRITICAL"}
    def evaluate(self, v=None, passed=True, day=dt.date(2026,10,24)):
        return gate.evaluate({"Results":[{"Vulnerabilities":[v or self.v]}]},self.policy,passed,day)[0]["disposition"]
    def test_exact_reviewed_finding_only(self):
        self.assertEqual("not_affected", self.evaluate())
        for field,value in [("VulnerabilityID","CVE-other"),("PkgName","another-library"),("InstalledVersion","6.2.18")]:
            self.assertEqual("action_required",self.evaluate(dict(self.v,**{field:value})))
    def test_missing_regression_expiry_and_future_review_fail_closed(self):
        self.assertEqual("action_required",self.evaluate(passed=False))
        self.assertEqual("action_required",self.evaluate(day=dt.date(2026,11,23)))
        self.assertEqual("action_required",self.evaluate(day=dt.date(2026,10,23)))
    def test_missing_test_receipts_fail_closed(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:self.assertFalse(gate.boundaries_passed(folder))
    def test_retired_policy_never_waives_a_finding(self):
        self.policy = {"retiredOn":"2026-10-25", "decisions":[]}
        self.assertEqual("action_required",self.evaluate())
        self.assertEqual("action_required",self.evaluate(dict(self.v,VulnerabilityID="CVE-other")))
        self.assertEqual([],gate.evaluate({"Results":[]},self.policy,False,dt.date(2026,12,1)))
    def test_failed_or_skipped_receipts_fail_closed(self):
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            for index,name in enumerate(gate.REQUIRED):
                Path(folder, f"TEST-{index}.xml").write_text(
                    f'<testsuite name="{name}" tests="3" failures="0" errors="0" skipped="0"/>')
            self.assertTrue(gate.boundaries_passed(folder))
            path = Path(folder, "TEST-0.xml")
            original = path.read_text()
            for attribute in ["failures", "errors", "skipped"]:
                path.write_text(original.replace(f'{attribute}="0"', f'{attribute}="1"'))
                self.assertFalse(gate.boundaries_passed(folder))
            path.write_text(original.replace('tests="3"','tests="2"'))
            self.assertFalse(gate.boundaries_passed(folder))
if __name__ == "__main__":unittest.main()
