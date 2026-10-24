"""Run on the disposable Linux demo host; secrets never leave that host.

Requires access to the ledger container's environment through Docker. This is an
operator acceptance probe, not an untrusted-client utility or a production issuer.
"""
import base64
import hashlib
import hmac
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    config = json.loads(subprocess.check_output(
        ["docker", "inspect", "ledgerline-ledger-service"], text=True))[0]
    environment = dict(item.split("=", 1) for item in config["Config"]["Env"] if "=" in item)
    secret = environment["JWT_SECRET"]

    def token(subject, roles, accounts=()):
        def encode(value):
            return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=")
        data = encode({"alg": "HS256", "typ": "JWT"}) + b"." + encode(
            {"sub": subject, "roles": roles, "accounts": accounts, "exp": int(time.time()) + 600})
        return (data + b"." + base64.urlsafe_b64encode(
            hmac.new(secret.encode(), data, hashlib.sha256).digest()).rstrip(b"=")).decode()

    count = 0

    def call(path, auth=None, body=None, key=None, expected=(200,), extra=None, port=8080):
        nonlocal count
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + auth
        if key:
            headers["Idempotency-Key"] = key
        headers.update(extra or {})
        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/v1/{path}",
            data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        if status not in expected:
            raise AssertionError(f"{request.method} {path}: HTTP {status}, expected {expected}")
        count += 1
        return json.loads(raw) if raw else None

    subject = "cloud-acceptance-" + uuid.uuid4().hex
    operator = token(subject + "-operator", ["OPERATOR"])
    customer = token(subject, ["CUSTOMER"])
    account = lambda auth, kind: call("accounts", auth, {"currency": "USD", "type": kind}, expected=(201,))
    a, b, funding = account(customer, "CUSTOMER"), account(customer, "CUSTOMER"), account(operator, "OVERDRAFT")
    request = {"fromAccountId": funding["id"], "toAccountId": a["id"], "amountMinor": 100000, "currency": "USD"}
    call("transfers", operator, request, uuid.uuid4().hex, expected=(200, 201))
    request.update(fromAccountId=a["id"], toAccountId=b["id"], amountMinor=12500)
    key = uuid.uuid4().hex
    original = call("transfers", customer, request, key, expected=(200, 201))
    replay = call("transfers", customer, request, key)
    assert original["transactionId"] == replay["transactionId"] and replay["idempotentReplay"]
    call("transfers", customer, dict(request, amountMinor=1), key, expected=(422,))
    call("accounts/" + a["id"], expected=(401,))
    call("accounts/" + a["id"], "invalid.jwt.token", expected=(401,))
    call("accounts/" + funding["id"], customer, expected=(403,))
    for kind in ("OVERDRAFT", "INTERNAL"):
        call("accounts", customer, {"currency": "USD", "type": kind}, expected=(403,))
    for amount in (0, -1, 0.29):
        call("transfers", customer, dict(request, amountMinor=amount), uuid.uuid4().hex, expected=(400,))
    call("transfers", customer, request, uuid.uuid4().hex, expected=(400,),
         extra={"X-Isolation-Variant": "VARIANT_0_BROKEN"})
    projection_token = token(subject, ["CUSTOMER"], [a["id"], b["id"]])
    call(f"projections/accounts/{funding['id']}/statement", projection_token, expected=(403,), port=8081)
    deadline = time.monotonic() + 60
    while True:
        statement = call(f"projections/accounts/{a['id']}/statement", projection_token, port=8081)
        if any(row["transactionId"] == original["transactionId"] for row in statement["items"]):
            break
        if time.monotonic() >= deadline:
            raise AssertionError("Cloud projection catch-up exceeded 60 seconds")
        time.sleep(1)
    for identifier, expected in ((a["id"], 87500), (b["id"], 12500), (funding["id"], -100000)):
        assert call("accounts/" + identifier, operator)["balanceMinor"] == expected
    print(json.dumps({"result": "PASS", "http_checks": count, "replay_original": True,
                      "projection_caught_up": True, "balances": [87500, 12500, -100000]}))


if __name__ == "__main__":
    main()
