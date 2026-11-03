#!/usr/bin/env python3
"""Exercise the actual production SpotBugs configuration on disposable Java code."""
import copy
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])


def main():
    parent = ET.parse(ROOT / "pom.xml")
    plugin = copy.deepcopy(parent.find(
        ".//m:profile[m:id='static-analysis']/m:build/m:plugins/m:plugin", NS))
    if plugin is None:
        raise AssertionError("Production static-analysis profile is missing")
    plugin.find("m:configuration/m:includeFilterFile", NS).text = str(
        ROOT / "config/spotbugs-include.xml")
    with tempfile.TemporaryDirectory(prefix="ledgerline-static-analysis-") as directory:
        project = Path(directory)
        pom = ET.fromstring('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion><groupId>verification</groupId>
          <artifactId>static-analysis-control</artifactId><version>1</version>
          <properties><maven.compiler.release>21</maven.compiler.release></properties>
          <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
          </plugin></plugins></build></project>''')
        pom.find("m:build/m:plugins", NS).append(plugin)
        ET.ElementTree(pom).write(project / "pom.xml", encoding="utf-8", xml_declaration=True)
        source = project / "src/main/java/DefectControl.java"
        source.parent.mkdir(parents=True)
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        command = [str(wrapper), "-f", str(project / "pom.xml"), "clean", "verify", "--batch-mode"]
        source.write_text('public class DefectControl { public int length(String value) { return value == null ? 0 : value.length(); } }', encoding="utf-8")
        good = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if good.returncode:
            raise AssertionError("Valid-code control failed:\n" + good.stdout[-12000:])
        source.write_text('public class DefectControl { public int length() { String value = null; return value.length(); } }', encoding="utf-8")
        bad = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        report = project / "target/spotbugsXml.xml"
        if not bad.returncode or not report.exists():
            raise AssertionError("Defect did not produce a rejected SpotBugs report:\n" + bad.stdout[-12000:])
        bugs = [bug.attrib["type"] for bug in ET.parse(report).iter("BugInstance")]
        if "NP_ALWAYS_NULL" not in bugs:
            raise AssertionError("Expected NP_ALWAYS_NULL; found " + repr(bugs) + "\n" + bad.stdout[-12000:])
        print("PASS: valid code accepted; definite null dereference rejected by production gate (NP_ALWAYS_NULL).")


if __name__ == "__main__":
    main()
