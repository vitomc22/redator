const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

function configuration(live, external = false, runtimeFailure = false, baseUrl = '') {
  const output = execFileSync(process.execPath, ['-e', `
    const config = require('./playwright.config.js');
    process.stdout.write(JSON.stringify({
      hasServer: Boolean(config.webServer),
      provider: config.webServer?.env.LLM_PROVIDER,
      data: config.webServer?.env.REDATOR_DATA_DIR,
      database: config.webServer?.env.SPRING_DATASOURCE_URL,
      reuseExistingServer: config.webServer?.reuseExistingServer,
      ollamaBaseUrl: config.webServer?.env.OLLAMA_BASE_URL,
      ragEnabled: config.webServer?.env.RAG_ENABLED,
      ragRequired: config.webServer?.env.RAG_REQUIRE_ON_STARTUP,
      verifyRuntime: config.webServer?.env.REDATOR_VERIFY_RUNTIME,
      autoPull: config.webServer?.env.OLLAMA_AUTO_PULL,
      baseURL: config.use.baseURL,
      readinessURL: config.webServer?.url,
      port: config.webServer?.env.SERVER_PORT
    }));
  `], { cwd: process.cwd(), env: { ...process.env, E2E_LIVE_OLLAMA: live ? '1' : '0',
    E2E_RUNTIME_FAILURE: runtimeFailure ? '1' : '0',
    PLAYWRIGHT_BASE_URL: baseUrl,
    PLAYWRIGHT_EXTERNAL_SERVER: external ? '1' : '0', CI: '' } });
  return JSON.parse(output.toString());
}

test('offline browser tests keep their controlled fake backend', () => {
  const config = configuration(false);
  assert.equal(config.provider, 'fake');
  assert.match(config.database, /^jdbc:sqlite:\.\/target\/playwright-offline-[a-f0-9-]+\.db$/);
  assert.notEqual(config.database, configuration(false).database);
  assert.equal(config.reuseExistingServer, false);
  assert.equal(config.baseURL, 'http://127.0.0.1:18080');
  assert.equal(config.port, '18080');
});

test('explicit external inspection does not start or attach a replacement backend', () => {
  assert.equal(configuration(false, true).hasServer, false);
  assert.equal(configuration(false, true).baseURL, 'http://127.0.0.1:8080');
});

test('a custom owned-server URL also controls readiness and the actual Spring port', () => {
  const config = configuration(false, false, false, 'http://127.0.0.1:18081');
  assert.equal(config.baseURL, 'http://127.0.0.1:18081');
  assert.equal(config.readinessURL, 'http://127.0.0.1:18081/api/runtime');
  assert.equal(config.port, '18081');
});

test('live browser tests start Ollama with a separate real SQLite collection', () => {
  const offline = configuration(false);
  const live = configuration(true);
  assert.equal(live.provider, 'ollama');
  assert.notEqual(live.data, offline.data);
  assert.notEqual(live.database, offline.database);
  assert.equal(live.reuseExistingServer, false);
});

test('transport failure tests use actual Ollama code, an unreachable local endpoint and a fresh SQLite', () => {
  const first = configuration(false, false, true);
  const second = configuration(false, false, true);
  assert.equal(first.provider, 'ollama');
  assert.equal(first.ollamaBaseUrl, 'http://127.0.0.1:1');
  assert.equal(first.ragEnabled, 'false');
  assert.equal(first.ragRequired, 'false');
  assert.equal(first.verifyRuntime, 'false');
  assert.equal(first.autoPull, 'false');
  assert.equal(first.reuseExistingServer, false);
  assert.notEqual(first.database, second.database);
  assert.notEqual(first.database, configuration(false).database);
});

test('real inference and deliberate transport failure cannot be enabled together', () => {
  assert.throws(() => configuration(true, false, true));
});
