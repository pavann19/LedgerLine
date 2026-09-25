import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const transferLatency = new Trend('cloud_transfer_latency_ms');
const successfulTransfers = new Counter('cloud_transfers_success');
const rejectedTransfers = new Counter('cloud_transfers_rejected');
const replayTransfers = new Counter('cloud_transfers_replayed');

export const options = {
  stages: [
    { duration: '30s', target: 25 },  // Ramp up to 25 VUs
    { duration: '1m', target: 50 },   // Sustained load at 50 VUs
    { duration: '30s', target: 100 },  // Spike to 100 VUs
    { duration: '30s', target: 0 },   // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<150', 'p(99)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.CLOUD_URL || 'http://localhost:8080/api/v1';
const ACCESS_TOKEN = __ENV.ACCESS_TOKEN;
const ACCOUNT_A = __ENV.ACCOUNT_A;
const ACCOUNT_B = __ENV.ACCOUNT_B;

if (!ACCESS_TOKEN || !ACCOUNT_A || !ACCOUNT_B) {
  throw new Error('ACCESS_TOKEN, ACCOUNT_A, and ACCOUNT_B are required');
}

export default function () {
  const amount = Math.floor(Math.random() * 100) + 10;
  const isReversal = Math.random() > 0.5;

  const payload = JSON.stringify({
    fromAccountId: isReversal ? ACCOUNT_B : ACCOUNT_A,
    toAccountId: isReversal ? ACCOUNT_A : ACCOUNT_B,
    amountMinor: amount,
    currency: 'USD',
  });

  const idempotencyKey = `cloud-load-${__VU}-${__ITER}-${Date.now()}`;
  const res = http.post(`${BASE_URL}/transfers`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'Authorization': `Bearer ${ACCESS_TOKEN}`,
    },
  });

  transferLatency.add(res.timings.duration);

  if (res.status === 201) {
    successfulTransfers.add(1);
  } else if (res.status === 200) {
    replayTransfers.add(1);
  } else {
    rejectedTransfers.add(1);
  }

  sleep(0.02);
}
