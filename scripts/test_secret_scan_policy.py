"""Verify fixture exclusions cannot hide a changed secret or another file."""
import json
from pathlib import Path
import re
import secrets
import subprocess
import tempfile

IMAGE = "ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"
ROOT = Path(__file__).resolve().parents[1]
FIXTURE = "ledger-service/src/test/java/com/ledgerline/ledger/security/JwtBoundaryTest.java"


def check():
    fixture = re.search(r'SECRET = "([^"]+)"', (ROOT / FIXTURE).read_text()).group(1)
    cases = [(FIXTURE, fixture, 0), (FIXTURE, secrets.token_urlsafe(32), 1),
             ("another-test/BoundaryTest.java", fixture, 1)]
    for name, value, expected in cases:
        with tempfile.TemporaryDirectory(prefix="ledgerline-scan-policy-") as directory:
            root = Path(directory)
            path = root / "source" / name
            path.parent.mkdir(parents=True)
            path.write_text(f'private static final String SECRET = "{value}";\n')
            result = subprocess.run(["docker", "run", "--rm", "-v", f"{root}:/work",
                "-v", f"{ROOT / '.gitleaks.toml'}:/policy.toml:ro", IMAGE,
                "dir", "/work/source", "--config", "/policy.toml", "--redact", "--no-banner",
                "--log-level", "error", "--report-format", "json", "--report-path", "/work/report.json"],
                capture_output=True, text=True, timeout=120)
            if result.returncode != expected:
                raise AssertionError(f"Unexpected scanner exit {result.returncode}, expected {expected}: {result.stderr}")
            findings = json.loads((root / "report.json").read_text())
            if bool(findings) != bool(expected):
                raise AssertionError("Scanner report did not match expected detection")
    print("PASS: exact fixture allowed; changed value and alternate path detected (3 controls)")


if __name__ == "__main__":
    check()
