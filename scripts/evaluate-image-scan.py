"""Fail on actionable HIGH/CRITICAL findings; retain every raw finding and disposition.
Only the exact reviewed component/version may receive the time-bounded decision.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import xml.etree.ElementTree as ET

REQUIRED = {"com.ledgerline.ledger.security.ViewRenderingBoundaryTest",
            "com.ledgerline.projection.security.ViewRenderingBoundaryTest"}

def boundaries_passed(directory):
    passed = set()
    for path in Path(directory).rglob("TEST-*.xml"):
        root = ET.parse(path).getroot()
        if root.get("name") in REQUIRED:
            if int(root.get("failures", "0")) or int(root.get("errors", "0")) or int(root.get("skipped", "0")):
                return False
            if int(root.get("tests", "0")) >= 3:
                passed.add(root.get("name"))
    return passed == REQUIRED

def evaluate(report, policy, tests_passed, today):
    reviewed = dt.date.fromisoformat(policy["reviewedOn"])
    expiry = dt.date.fromisoformat(policy["expiresOn"])
    valid = reviewed <= today < expiry and 0 < (expiry-reviewed).days <= 31 and tests_passed
    findings = []
    for result in report.get("Results", []):
        for vuln in result.get("Vulnerabilities", []):
            if vuln.get("Severity") not in ("HIGH", "CRITICAL"):
                continue
            decision = next((d for d in policy["decisions"] if
                (d["id"], d["package"], d["version"], d["status"]) ==
                (vuln["VulnerabilityID"], vuln["PkgName"], vuln["InstalledVersion"], "not_affected")), None)
            disposition = "not_affected" if valid and decision else "action_required"
            findings.append({"id": vuln["VulnerabilityID"], "package": vuln["PkgName"],
                "version": vuln["InstalledVersion"], "severity": vuln["Severity"], "disposition": disposition})
    return findings

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--policy", type=Path, default=Path("security/vulnerability-applicability.json"))
    parser.add_argument("--test-reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8'))
    if not report.get("ArtifactID") or "Results" not in report:
        raise ValueError("Expected a complete raw Trivy image report")
    findings = evaluate(report, json.loads(args.policy.read_text(encoding='utf-8')), boundaries_passed(args.test_reports), dt.date.today())
    args.output.write_text(json.dumps({"artifact": report["ArtifactID"], "findings": findings}, indent=2))
    print(json.dumps(findings, indent=2))
    return 1 if any(v["disposition"] == "action_required" for v in findings) else 0

if __name__ == "__main__":
    raise SystemExit(main())
