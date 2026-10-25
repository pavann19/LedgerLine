"""Observe health inside the disposable VM without a laptop SSH tunnel."""
import json
import time
import urllib.request

deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    try:
        statuses = []
        for port in (8080, 8081):
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=5) as response:
                statuses.append(json.load(response)['status'])
        if statuses == ['UP', 'UP']:
            print(json.dumps({'result': 'PASS', 'ledger': 'UP', 'projection': 'UP'}))
            break
    except Exception:
        pass
    time.sleep(5)
else:
    raise RuntimeError('Services did not reach health UP inside the cloud VM within 600 seconds')
