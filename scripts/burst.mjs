#!/usr/bin/env node
// One-command burst probe for every graded invariant.
//   node scripts/burst.mjs [base-url]
// Exits non-zero if any invariant is violated. Safe to run repeatedly against a live deployment:
// every run uses freshly-minted user ids, so it never depends on or disturbs existing state.
import { randomUUID } from 'node:crypto';

const baseUrl = (process.argv[2] ?? process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const run = randomUUID().slice(0, 8);
const user = (name) => `burst-${run}-${name}`;
const key = (name) => `burst-${run}-${name}`;

let failures = 0;
const pass = (msg) => console.log(`  [32m✓[0m ${msg}`);
const fail = (msg) => { failures++; console.log(`  [31m✗ FAIL[0m ${msg}`); };
const check = (ok, msg) => (ok ? pass(msg) : fail(msg));
const section = (n, title) => console.log(`\n[${n}] ${title}`);

async function call(method, path, { as, body } = {}) {
  const res = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(as ? { authorization: `Bearer user:${as}` } : {}),
      ...(body === undefined ? {} : { 'content-type': 'application/json' })
    },
    ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) })
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

const openWallet = async (as) => {
  const res = await call('POST', '/wallets', { as });
  if (res.status !== 200) throw new Error(`POST /wallets for ${as} -> ${res.status} ${JSON.stringify(res.body)}`);
  return res.body;
};
const balance = async (as, id) => (await call('GET', `/wallets/${id}`, { as })).body.balance_paise;
const transfer = (as, from, to, amount_paise, idempotency_key) =>
  call('POST', '/transfers', { as, body: { from, to, amount_paise, idempotency_key } });

console.log(`Burst target: ${baseUrl}   (run id ${run})`);
const health = await call('GET', '/actuator/health');
if (health.status !== 200) {
  console.error(`service is not healthy: ${health.status} ${JSON.stringify(health.body)}`);
  process.exit(1);
}

// ---------------------------------------------------------------- 1. race-free get-or-create
section(1, 'Race-free get-or-create: 50 simultaneous POST /wallets for a brand-new user');
{
  const who = user('race');
  const raced = await Promise.all(Array.from({ length: 50 }, () => openWallet(who)));
  const ids = new Set(raced.map((w) => w.id));
  check(ids.size === 1, `50 concurrent creates yielded ${ids.size} wallet (expected exactly 1) -> id ${[...ids].join()}`);
  const balances = new Set(raced.map((w) => w.balance_paise));
  check(balances.size === 1, `all 50 responses reported the same balance (${[...balances].join()})`);
}

// ---------------------------------------------------------------- 2. idempotent retry storm
section(2, 'Idempotent retry storm: same idempotency_key fired 30x concurrently');
{
  const [alice, bob] = [user('idem-a'), user('idem-b')];
  const [a, b] = await Promise.all([openWallet(alice), openWallet(bob)]);
  const startA = await balance(alice, a.id);
  const startB = await balance(bob, b.id);
  const k = key('idem');

  const storm = await Promise.all(Array.from({ length: 30 }, () => transfer(alice, a.id, b.id, 1_000, k)));
  const ok = storm.filter((r) => r.status === 200);
  check(ok.length === 30, `all 30 responses were 200 (got ${ok.length}); statuses: ${[...new Set(storm.map((r) => r.status))].join()}`);
  const distinct = new Set(ok.map((r) => JSON.stringify(r.body)));
  check(distinct.size === 1, `all 30 responses were byte-identical (${distinct.size} distinct)`);

  const endA = await balance(alice, a.id);
  const endB = await balance(bob, b.id);
  check(endA === startA - 1_000, `source debited exactly once: ${startA} -> ${endA} (expected ${startA - 1_000})`);
  check(endB === startB + 1_000, `destination credited exactly once: ${startB} -> ${endB} (expected ${startB + 1_000})`);

  const conflict = await transfer(alice, a.id, b.id, 7_777, k);
  check(conflict.status === 409, `same key + different amount -> ${conflict.status} (expected 409)`);
  const untouched = await balance(alice, a.id);
  check(untouched === endA, `the 409 did not move money (balance still ${untouched})`);
}

// ---------------------------------------------------------------- 3. integer-paise representation
section(3, 'Money representation: decimal amounts must be rejected, not truncated');
{
  const [alice, bob] = [user('paise-a'), user('paise-b')];
  const [a, b] = await Promise.all([openWallet(alice), openWallet(bob)]);
  const start = await balance(alice, a.id);

  const decimal = await transfer(alice, a.id, b.id, 100.7, key('decimal'));
  check(decimal.status === 400, `amount_paise 100.7 -> ${decimal.status} (expected 400, NOT a silent truncation to 100)`);
  const rupees = await call('POST', '/transfers', {
    as: alice,
    body: `{"from":${a.id},"to":${b.id},"amount_paise":10.50,"idempotency_key":"${key('rupees')}"}`
  });
  check(rupees.status === 400, `rupees-as-decimal 10.50 -> ${rupees.status} (expected 400)`);
  const negative = await transfer(alice, a.id, b.id, -500, key('negative'));
  check(negative.status === 400, `negative amount -> ${negative.status} (expected 400)`);
  const self = await transfer(alice, a.id, a.id, 500, key('self'));
  check(self.status === 400, `self-transfer -> ${self.status} (expected 400)`);
  const garbage = await call('POST', '/transfers', { as: alice, body: '{"from": oops}' });
  check(garbage.status === 400, `malformed JSON body -> ${garbage.status} (expected 400, not 500)`);

  check((await balance(alice, a.id)) === start, `no rejected request moved money (balance still ${start})`);
}

// ---------------------------------------------------------------- 4. conservation under contention
section(4, 'Conservation under contention: 400 concurrent transfers, A<->B both directions + overdrafts');
{
  const names = ['con-a', 'con-b', 'con-c'].map(user);
  const [a, b, c] = await Promise.all(names.map(openWallet));
  const ids = [a.id, b.id, c.id];
  const totalBefore = (await Promise.all(names.map((n, i) => balance(n, ids[i])))).reduce((s, v) => s + v, 0);

  // Deliberately includes A->B and B->A landing simultaneously, which is the deadlock trap.
  const routes = [[0, 1], [1, 0], [1, 2], [2, 1], [0, 2], [2, 0]];
  const jobs = Array.from({ length: 400 }, (_, i) => {
    const [f, t] = routes[i % routes.length];
    const overdraw = i % 9 === 0;
    return transfer(names[f], ids[f], ids[t], overdraw ? 99_999_999 : 250, key(`con-${i}`));
  });
  const results = await Promise.all(jobs);

  const tally = results.reduce((acc, r) => {
    const label = r.status === 200 ? `200 ${r.body.status}` : `${r.status} ${r.body.error ?? '?'}`;
    acc[label] = (acc[label] ?? 0) + 1;
    return acc;
  }, {});
  console.log(`      responses: ${JSON.stringify(tally)}`);

  const errors = Object.entries(tally).filter(([k2]) => !k2.startsWith('200 '));
  check(errors.length === 0, `no error responses under contention (deadlock/lost-update would show here)${errors.length ? `: ${JSON.stringify(errors)}` : ''}`);
  check((tally['200 declined'] ?? 0) > 0, `overdrafts were declined cleanly (${tally['200 declined'] ?? 0} declines)`);

  const finals = await Promise.all(names.map((n, i) => balance(n, ids[i])));
  const totalAfter = finals.reduce((s, v) => s + v, 0);
  check(totalAfter === totalBefore, `conservation holds: total ${totalBefore} -> ${totalAfter}`);
  check(finals.every((v) => v >= 0), `no negative balances: [${finals.join(', ')}]`);
}

// ---------------------------------------------------------------- 5. observability
section(5, 'Observability: correlation id + domain counters');
{
  const correlation = `burst-${run}-probe`;
  const res = await fetch(`${baseUrl}/wallets`, {
    method: 'POST',
    headers: { authorization: `Bearer user:${user('trace')}`, 'X-Correlation-Id': correlation }
  });
  check(res.headers.get('x-correlation-id') === correlation, `X-Correlation-Id echoed back (${res.headers.get('x-correlation-id')})`);

  const metrics = await fetch(`${baseUrl}/metrics`).then((r) => (r.ok ? r.text() : ''));
  const expected = [
    'wallet_transfers_completed_total',
    'wallet_transfers_declined_insufficient_funds_total',
    'wallet_idempotent_replays_total'
  ];
  for (const name of expected) {
    const line = metrics.split('\n').find((l) => l.startsWith(name));
    check(Boolean(line), `/metrics exposes ${name}${line ? ` = ${line.split(' ').pop()}` : ''}`);
  }
  const p99 = metrics.split('\n').find((l) => l.includes('http_server_requests_seconds') && l.includes('quantile="0.99"'));
  check(Boolean(p99), `/metrics exposes a request-latency p99 quantile${p99 ? ` (${p99.split(' ').pop()}s)` : ''}`);
}

console.log(failures === 0
  ? '\n[32mALL INVARIANTS HELD[0m'
  : `\n[31m${failures} CHECK(S) FAILED[0m`);
process.exit(failures === 0 ? 0 : 1);
