// k6 load test for POST /v1/decisions.
//   docker run --rm --network fraud-platform_default -v "$PWD/perf:/scripts" grafana/k6:1.3.0 run \
//       -e SCENARIO=baseline -e BASE_URL=http://decision-service:8080 /scripts/scoring.js
//
// Scenarios (SCENARIO env):
//   baseline     60 s warm-up at 50 rps, then 150 rps for 3 min   (NFR-01: p95 <= 100 ms, p99 <= 250 ms at 150 TPS)
//   stress       ramping arrival rate 50 -> 600 rps over 5 min      (find the knee: where latency/errors take off)
//   degradation  150 rps for 4 min while perf/run-degradation.sh injects dependency failures
// Open-model executors (constant/ramping-arrival-rate): requests keep arriving even if the server slows down,
// which is how real payment traffic behaves (closed models hide latency problems — coordinated omission).
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'baseline';

const pools = {
  'aldermoor-bank': new SharedArray('aldermoor', () => JSON.parse(open('./data/pool-aldermoor-bank.json'))),
  'quillon-pay': new SharedArray('quillon', () => JSON.parse(open('./data/pool-quillon-pay.json'))),
};
const keys = { 'aldermoor-bank': 'dev-aldermoor-gateway-key', 'quillon-pay': 'dev-quillon-gateway-key' };

const decisions = new Counter('decisions');
const degraded = new Rate('degraded_decisions');
const serverDecisionMs = new Trend('server_decision_ms', true);

const scenarios = {
  baseline: {
    warmup: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '60s', preAllocatedVUs: 20, maxVUs: 200 },
    sustained: { executor: 'constant-arrival-rate', rate: 150, timeUnit: '1s', duration: '180s', startTime: '60s',
                 preAllocatedVUs: 50, maxVUs: 400 },
  },
  stress: {
    ramp: { executor: 'ramping-arrival-rate', startRate: 50, timeUnit: '1s', preAllocatedVUs: 100, maxVUs: 1500,
            stages: [{ target: 150, duration: '60s' }, { target: 300, duration: '60s' }, { target: 450, duration: '60s' },
                     { target: 600, duration: '60s' }, { target: 600, duration: '60s' }] },
  },
  calibration: {
    steady: { executor: 'constant-arrival-rate', rate: 30, timeUnit: '1s', duration: '90s', preAllocatedVUs: 10, maxVUs: 100 },
  },
  degradation: {
    sustained: { executor: 'constant-arrival-rate', rate: 150, timeUnit: '1s', duration: '240s', preAllocatedVUs: 50, maxVUs: 600 },
  },
};

export const options = {
  scenarios: scenarios[SCENARIO],
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: SCENARIO === 'baseline'
    ? {
        'http_req_duration{scenario:sustained}': ['p(95)<100', 'p(99)<250'],
        'http_req_failed{scenario:sustained}': ['rate<0.001'],
      }
    : { http_req_failed: ['rate<0.01'] },
  discardResponseBodies: false,
};

export default function () {
  const tenant = Math.random() < 0.7 ? 'aldermoor-bank' : 'quillon-pay';
  const pool = pools[tenant];
  const tpl = pool[Math.floor(Math.random() * pool.length)];
  const id = `K6-${__VU}-${__ITER}-${Date.now()}`;
  const body = Object.assign({}, tpl, { transactionId: id, eventTime: new Date().toISOString() });
  const res = http.post(`${BASE_URL}/v1/decisions`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'X-Api-Key': keys[tenant], 'Idempotency-Key': id },
    tags: { tenant },
  });
  const ok = check(res, { 'status 200': (r) => r.status === 200 });
  if (ok) {
    const d = res.json();
    decisions.add(1, { decision: d.decision, tenant });
    degraded.add(d.degradedModes.length > 0);
    serverDecisionMs.add(d.processingTimeMs);
  }
}

export function handleSummary(data) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return {
    [`/scripts/results/raw/${SCENARIO}-${stamp}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const d = (name) => (m[name] && m[name].values) || {};
  const lines = [
    `scenario=${SCENARIO}`,
    `requests=${d('http_reqs').count} rate=${(d('http_reqs').rate || 0).toFixed(1)}/s failed=${((d('http_req_failed').rate || 0) * 100).toFixed(3)}%`,
    `client latency ms: p50=${fmt(d('http_req_duration').med)} p95=${fmt(d('http_req_duration')['p(95)'])} p99=${fmt(d('http_req_duration')['p(99)'])} max=${fmt(d('http_req_duration').max)}`,
    `server decision ms: p50=${fmt(d('server_decision_ms').med)} p95=${fmt(d('server_decision_ms')['p(95)'])} p99=${fmt(d('server_decision_ms')['p(99)'])}`,
    `degraded decisions=${((d('degraded_decisions').rate || 0) * 100).toFixed(2)}%`,
  ];
  for (const [name, metric] of Object.entries(m)) {
    if (name.startsWith('http_req_duration{scenario:')) {
      lines.push(`${name}: p95=${fmt(metric.values['p(95)'])} p99=${fmt(metric.values['p(99)'])}`);
    }
  }
  const thresholds = Object.entries(m).filter(([, v]) => v.thresholds)
    .map(([k, v]) => `${k} ${Object.entries(v.thresholds).map(([t, r]) => `${t}:${r.ok ? 'PASS' : 'FAIL'}`).join(' ')}`);
  return lines.concat(thresholds).join('\n') + '\n';
}

function fmt(v) {
  return v === undefined ? 'n/a' : v.toFixed(1);
}
