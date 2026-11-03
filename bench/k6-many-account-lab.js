// Separately labelled many-account scenario. Original k6-cloud-load.js is unchanged.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
const success = new Counter('many_account_transfers_201');
const latency = new Trend('many_account_latency_ms');
export const options = {
  stages: [{duration:'10s',target:10},{duration:'40s',target:20},{duration:'10s',target:0}],
  thresholds: {http_req_duration:['p(95)<750','p(99)<1500'],http_req_failed:['rate<0.01']},
};
const accounts=JSON.parse(__ENV.ACCOUNT_IDS || '[]');
export default function () {
  if(accounts.length<20 || !__ENV.ACCESS_TOKEN) throw new Error('20 labelled synthetic accounts and token required');
  const from=Math.floor(Math.random()*accounts.length);
  const to=(from+1+Math.floor(Math.random()*(accounts.length-1)))%accounts.length;
  const response=http.post(`${__ENV.CLOUD_URL}/transfers`,JSON.stringify({fromAccountId:accounts[from],toAccountId:accounts[to],amountMinor:1,currency:'USD'}),{
    headers:{'Content-Type':'application/json',Authorization:`Bearer ${__ENV.ACCESS_TOKEN}`,'Idempotency-Key':`many-${__VU}-${__ITER}-${Date.now()}`},
  });
  latency.add(response.timings.duration);
  if(response.status===201) success.add(1);
  sleep(0.02);
}
