const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const { createApiClient } = require('../src/main/resources/static/api-client.js');

test('reviewer headers preserve JSON content type, body and HTTP method', async () => {
  let sent;
  const api = createApiClient(async (url, options) => {
    sent = { url, options };
    return { ok: true, json: async () => ({ status: 'DRAFT' }) };
  });
  const body = JSON.stringify({ scores: { C3: 160 } });
  assert.deepEqual(await api('/reviewer/review-requests/1/draft', {
    method: 'POST', headers: { 'X-Reviewer-Id': 'reviewer-test' }, body
  }), { status: 'DRAFT' });
  assert.equal(sent.url, '/api/reviewer/review-requests/1/draft');
  assert.equal(sent.options.headers['Content-Type'], 'application/json');
  assert.equal(sent.options.headers['X-Reviewer-Id'], 'reviewer-test');
  assert.equal(sent.options.method, 'POST');
  assert.equal(sent.options.body, body);
});

test('an explicitly supplied content type remains authoritative', async () => {
  const api = createApiClient(async (url, options) => {
    assert.equal(options.headers['Content-Type'], 'application/vnd.redator.test+json');
    return { ok: true, json: async () => ({}) };
  });
  await api('/example', { headers: { 'Content-Type': 'application/vnd.redator.test+json' } });
});

test('HTTP errors retain their code, run identity and status', async () => {
  const api = createApiClient(async () => ({ ok: false, status: 503,
    json: async () => ({ error: 'Runtime indisponível.', code: 'RUNTIME_UNAVAILABLE', runId: 7 }) }));
  await assert.rejects(api('/essays/1/evaluate', { method: 'POST' }), error => {
    assert.equal(error.message, 'Runtime indisponível.');
    assert.equal(error.code, 'RUNTIME_UNAVAILABLE');
    assert.equal(error.runId, 7);
    assert.equal(error.status, 503);
    return true;
  });
});

test('the browser adapter uses the current fetch wrapper for local profile headers', async () => {
  const context = vm.createContext({ fetch: async () => { throw new Error('Unscoped fetch must not be captured at load time'); } });
  vm.runInContext(readFileSync('src/main/resources/static/api-client.js', 'utf8'), context);
  context.fetch = async () => ({ ok: true, json: async () => ({ localUserApplied: true }) });
  assert.equal((await vm.runInContext('api("/me/progress")', context)).localUserApplied, true);
});
