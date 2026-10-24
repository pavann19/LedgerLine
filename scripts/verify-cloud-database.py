"""Run a supplied SQL assertion file on the disposable demo host.

The privileged operator reads the ephemeral database credentials locally from
Docker. Credentials are never sent back to the caller or embedded in CLI args.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse


def main():
    config = json.loads(subprocess.check_output(
        ["docker", "inspect", "ledgerline-ledger-service"], text=True))[0]
    environment = dict(item.split("=", 1) for item in config["Config"]["Env"] if "=" in item)
    url = urllib.parse.urlsplit(environment["SPRING_DATASOURCE_URL"].removeprefix("jdbc:"))
    result = subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-P", "pager=off",
        "-h", url.hostname, "-p", str(url.port or 5432),
        "-U", environment["SPRING_DATASOURCE_USERNAME"], "-d", url.path.lstrip("/")],
        input=Path(sys.argv[1]).read_text(), text=True,
        env={**os.environ, "PGPASSWORD": environment["SPRING_DATASOURCE_PASSWORD"], "PGSSLMODE": "require"})
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
