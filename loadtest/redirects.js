// Redirect load test: open model (constant arrival rate), power-law access over seeded codes.
//   k6 run -e BASE=http://app:8080 -e CODES=/scripts/codes_mongo.json -e RATE=1000 -e DURATION=60s \
//          -e SUMMARY=/results/raw/x.json redirects.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const codes = new SharedArray('codes', () => JSON.parse(open(__ENV.CODES || './codes.json')));
const BASE = __ENV.BASE || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '60s';
// Skew exponent: 1 = uniform, 3 = default power-law-like (a few codes get most of the traffic)
const SKEW = Number(__ENV.SKEW || 3);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    redirects: {
      // Open model: requests start at RATE/s regardless of response time, so overload shows up
      // as latency and errors instead of being hidden (no coordinated omission).
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(Math.max(100, RATE / 5), 1000),
      maxVUs: 4000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<50'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function skewedIndex(n) {
  return Math.floor(n * Math.pow(Math.random(), SKEW));
}

export default function () {
  const code = codes[skewedIndex(codes.length)];
  // redirects: 0 → measure our 302, not the destination site
  const res = http.get(`${BASE}/${code}`, { redirects: 0, tags: { name: 'redirect' } });
  check(res, { 'is 302': (r) => r.status === 302 });
}

export function handleSummary(data) {
  const out = {};
  if (__ENV.SUMMARY) {
    out[__ENV.SUMMARY] = JSON.stringify(data, null, 2);
  }
  const d = data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const reqs = data.metrics.http_reqs.values;
  const dropped = data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0;
  out.stdout = `rate=${RATE}/s achieved=${reqs.rate.toFixed(0)}/s p50=${d.med.toFixed(2)}ms ` +
    `p95=${d['p(95)'].toFixed(2)}ms p99=${d['p(99)'].toFixed(2)}ms errors=${(failed * 100).toFixed(2)}% dropped=${dropped}\n`;
  return out;
}
