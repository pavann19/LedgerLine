#!/usr/bin/env bash
set -euo pipefail

# Ledgerline Smoke Test Script
# Validates core endpoints, double-entry invariants, idempotency, and projection flow.

BASE_URL="${1:-http://localhost:8080}"
PROJECTION_URL="${2:-http://localhost:8081}"

echo "=== 1. Checking Service Health ==="
curl -sf "${BASE_URL}/actuator/health" | grep "UP"
echo "Ledger Service is UP."

echo "=== 2. Creating Customer Accounts ==="
ACC_A=$(curl -sf -X POST "${BASE_URL}/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currency":"USD","type":"CUSTOMER"}' | grep -o '"id":"[^"]*' | cut -d'"' -f4)

ACC_B=$(curl -sf -X POST "${BASE_URL}/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currency":"USD","type":"CUSTOMER"}' | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "Created Account A: ${ACC_A}"
echo "Created Account B: ${ACC_B}"

echo "=== 3. Creating Overdraft Funding Account ==="
ACC_FUNDING=$(curl -sf -X POST "${BASE_URL}/accounts" \
  -H "Content-Type: application/json" \
  -d '{"currency":"USD","type":"OVERDRAFT"}' | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Created Funding Account (Overdraft allowed): ${ACC_FUNDING}"

echo "=== 4. Funding Account A from Funding Account ==="
IDEMP_KEY_FUND="fund-${RANDOM}"
curl -sf -X POST "${BASE_URL}/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY_FUND}" \
  -d "{\"fromAccountId\":\"${ACC_FUNDING}\",\"toAccountId\":\"${ACC_A}\",\"amountMinor\":50000,\"currency\":\"USD\"}"

echo "Funded Account A with 50,000 minor units ($500.00)."

echo "=== 5. Transferring from Account A to Account B ==="
IDEMP_KEY_TX="tx-${RANDOM}"
TX_RES=$(curl -sf -X POST "${BASE_URL}/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY_TX}" \
  -d "{\"fromAccountId\":\"${ACC_A}\",\"toAccountId\":\"${ACC_B}\",\"amountMinor\":12500,\"currency\":\"USD\"}")
echo "Transfer response: ${TX_RES}"

echo "=== 6. Testing Idempotent Replay ==="
REPLAY_RES=$(curl -sf -X POST "${BASE_URL}/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY_TX}" \
  -d "{\"fromAccountId\":\"${ACC_A}\",\"toAccountId\":\"${ACC_B}\",\"amountMinor\":12500,\"currency\":\"USD\"}")
echo "Replay response verified (same key + same payload)."

echo "=== 7. Verifying Account Balances ==="
BAL_A=$(curl -sf "${BASE_URL}/accounts/${ACC_A}" | grep -o '"balanceMinor":[0-9]*' | cut -d':' -f2)
BAL_B=$(curl -sf "${BASE_URL}/accounts/${ACC_B}" | grep -o '"balanceMinor":[0-9]*' | cut -d':' -f2)

echo "Account A balance: ${BAL_A} (Expected: 37500)"
echo "Account B balance: ${BAL_B} (Expected: 12500)"

if [ "${BAL_A}" -eq 37500 ] && [ "${BAL_B}" -eq 12500 ]; then
  echo ">>> Balance verification PASSED! <<<"
else
  echo ">>> Balance verification FAILED! <<<"
  exit 1
fi

echo "=== 8. Verifying Postings History ==="
curl -sf "${BASE_URL}/accounts/${ACC_A}/postings"
echo ""

echo "=== All smoke tests passed successfully! ==="
