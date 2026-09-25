#!/usr/bin/env python3
"""
Seeds real accounts for bench/k6-isolation-test.js.

The ledger API has no "create account with an initial balance" endpoint by design
(accounts always start at zero; funding only happens through balanced postings) and
account IDs are server-generated UUIDs, not client-chosen. So a benchmark script can't
just hardcode a list of UUIDs and expect them to exist. This script creates real accounts
via POST /accounts, funds them directly against Postgres (the same shortcut the test
suite's `fundAccount` helpers use — bypassing the ledger to seed starting capital is not
itself part of what's under test), and writes the resulting IDs to a JSON file that the
k6 script reads at init time via `open()`.
"""
import json
import os
import subprocess
import sys
import urllib.request

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080/api/v1")
ACCESS_TOKEN = os.environ["ACCESS_TOKEN"]
DB_CONTAINER = os.environ.get("DB_CONTAINER", "ledgerline-postgres")
DB_NAME = os.environ.get("DB_NAME", "ledgerline")
DB_USER = os.environ.get("DB_USER", "postgres")
FUND_AMOUNT_MINOR = int(os.environ.get("FUND_AMOUNT_MINOR", "5000000000"))  # $50,000,000.00
LOW_CONTENTION_COUNT = 50
HIGH_CONTENTION_COUNT = 3


def create_account():
    req = urllib.request.Request(
        f"{BASE_URL}/accounts",
        data=json.dumps({"currency": "USD", "type": "CUSTOMER"}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {ACCESS_TOKEN}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read())
        return body["id"]


def fund_account(account_id: str):
    sql = f"UPDATE account_balances SET balance_minor = balance_minor + {FUND_AMOUNT_MINOR} WHERE account_id = '{account_id}';"
    subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-c", sql],
        check=True,
        capture_output=True,
    )


def seed(count: int, label: str) -> list[str]:
    ids = []
    for i in range(count):
        account_id = create_account()
        fund_account(account_id)
        ids.append(account_id)
        print(f"  [{label}] {i + 1}/{count} -> {account_id}", file=sys.stderr)
    return ids


def main():
    print(f"Seeding {LOW_CONTENTION_COUNT} low-contention accounts...", file=sys.stderr)
    low_ids = seed(LOW_CONTENTION_COUNT, "low")

    print(f"Seeding {HIGH_CONTENTION_COUNT} high-contention accounts...", file=sys.stderr)
    high_ids = seed(HIGH_CONTENTION_COUNT, "high")

    out_dir = os.path.join(os.path.dirname(__file__), "results")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "seed-accounts.json")
    with open(out_path, "w") as f:
        json.dump({"low": low_ids, "high": high_ids}, f, indent=2)

    print(f"Wrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
