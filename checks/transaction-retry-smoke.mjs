// Run only against a disposable backend/database; this creates two test accounts.
// RETRY_TEST_BASE_URL=http://retry-backend:8080 node checks/transaction-retry-smoke.mjs
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const base = process.env.RETRY_TEST_BASE_URL;
assert.ok(base, 'Set RETRY_TEST_BASE_URL to a disposable backend');
for (let attempt = 0; ; attempt++) {
  try {
    if ((await fetch(`${base}/api/v1/portfolios`)).status === 401) break;
  } catch {}
  assert.ok(attempt < 60, 'Test backend did not become ready');
  await new Promise((resolve) => setTimeout(resolve, 1000));
}

async function login() {
  const username = `retry-smoke-${randomUUID()}`;
  const password = randomUUID();
  const registered = await fetch(`${base}/api/v1/users/createUser`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, passwordHash: password }),
  });
  assert.equal(registered.status, 200);
  const loggedIn = await fetch(`${base}/login`, {
    method: 'POST', body: new URLSearchParams({ username, password }),
  });
  assert.equal(loggedIn.status, 200);
  return loggedIn.headers.get('set-cookie').split(';')[0];
}

const cookie = await login();
const createdPortfolio = await fetch(`${base}/api/v1/createPortfolio`, {
  method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' },
  body: JSON.stringify({ portfolioName: 'Retry test' }),
});
assert.equal(createdPortfolio.status, 200);
const { portfolioId } = await createdPortfolio.json();
const endpoint = `${base}/api/v1/${encodeURIComponent(portfolioId)}/createTransaction`;
const key = randomUUID();
const payload = { ticker: 'EUR', transactionType: 'DEPOSIT', amount: '25',
  quantity: '0', price: '0', commission: '0', date: '2026-10-20', currency: 'EUR' };
const write = (body = payload, session = cookie) => fetch(endpoint, {
  method: 'POST', headers: { Cookie: session, 'Content-Type': 'application/json', 'Idempotency-Key': key },
  body: JSON.stringify(body),
});

const preflight = await fetch(endpoint, { method: 'OPTIONS', headers: {
  Origin: 'http://localhost:3000', 'Access-Control-Request-Method': 'POST',
  'Access-Control-Request-Headers': 'content-type,idempotency-key',
} });
assert.equal(preflight.status, 200);
assert.match(preflight.headers.get('access-control-allow-headers'), /idempotency-key/i);
const responses = await Promise.all(Array.from({ length: 6 }, () => write()));
const rows = await Promise.all(responses.map(async (response) => {
  assert.equal(response.status, 200);
  return (await response.json()).save;
}));
assert.equal(new Set(rows.map((row) => row.transactionId)).size, 1);
assert.equal((await write({ ...payload, amount: '26' })).status, 409);
assert.equal((await write(payload, '')).status, 401);
assert.equal((await write(payload, await login())).status, 403);
const list = await fetch(`${base}/api/v1/${encodeURIComponent(portfolioId)}/transactions`, {
  headers: { Cookie: cookie },
});
assert.equal(list.status, 200);
assert.equal((await list.json()).length, 1);
console.log('PASS: HTTP retries, concurrent creation, payload conflict, CORS, authentication and ownership');
