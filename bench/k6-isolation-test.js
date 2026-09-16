import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// Metrics
const transferLatency = new Trend('transfer_latency_ms');
const transferSuccess = new Counter('transfers_success');
const transferInsufficientFunds = new Counter('transfers_insufficient_funds');
const transferRetries = new Counter('transfers_retries');
const transferErrors = new Counter('transfers_errors');

export const options = {
  scenarios: {
    low_contention: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      env: { CONTENTION: 'LOW' },
    },
    high_contention: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      startTime: '35s',
      env: { CONTENTION: 'HIGH' },
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100'], // 95% of requests must complete below 100ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VARIANT = __ENV.VARIANT || 'VARIANT_1_PESSIMISTIC';

// Pre-created accounts list (setup step in a real run)
const LOW_CONTENTION_ACCOUNTS = Array.from({ length: 50 }, (_, i) => `00000000-0000-0000-0000-${String(i + 1).padStart(12, '0')}`);
const HIGH_CONTENTION_ACCOUNTS = Array.from({ length: 3 }, (_, i) => `00000000-0000-0000-0000-${String(i + 1).padStart(12, '0')}`);

export default function () {
  const isHigh = __ENV.CONTENTION === 'HIGH';
  const accounts = isHigh ? HIGH_CONTENTION_ACCOUNTS : LOW_CONTENTION_ACCOUNTS;

  const fromIdx = Math.floor(Math.random() * accounts.length);
  let toIdx = Math.floor(Math.random() * accounts.length);
  while (toIdx === fromIdx) {
    toIdx = Math.floor(Math.random() * accounts.length);
  }

  const payload = JSON.stringify({
    fromAccountId: accounts[fromIdx],
    toAccountId: accounts[toIdx],
    amountMinor: Math.floor(Math.random() * 50) + 1,
    currency: 'USD',
  });

  const idempotencyKey = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Isolation-Variant': VARIANT,
    },
  };

  const res = http.post(`${BASE_URL}/transfers`, payload, params);
  transferLatency.add(res.timings.duration);

  if (res.status === 201 || res.status === 200) {
    transferSuccess.add(1);
  } else if (res.status === 422) {
    transferInsufficientFunds.add(1);
  } else {
    transferErrors.add(1);
  }

  sleep(0.01);
}
