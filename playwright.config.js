const { defineConfig, devices } = require('@playwright/test');
const { randomUUID } = require('node:crypto');
const liveOllama = process.env.E2E_LIVE_OLLAMA === '1';
const runtimeFailure = process.env.E2E_RUNTIME_FAILURE === '1';
if (liveOllama && runtimeFailure) throw new Error('Real inference and deliberate runtime failure are mutually exclusive.');
const externalServer = process.env.PLAYWRIGHT_EXTERNAL_SERVER === '1';
const baseURL = (process.env.PLAYWRIGHT_BASE_URL || `http://127.0.0.1:${externalServer ? '8080' : '18080'}`).replace(/\/$/, '');
const isolatedCollection = `./target/playwright-${runtimeFailure ? 'runtime-failure' : liveOllama ? 'live' : 'offline'}-${randomUUID()}`;

module.exports = defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  timeout: 30_000,
  expect: { timeout: 8_000 },
  outputDir: 'test-results',
  webServer: externalServer ? undefined : {
    command: 'mvn spring-boot:run',
    url: `${baseURL}/api/runtime`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      ...process.env,
      SERVER_PORT: new URL(baseURL).port || '80',
      SERVER_ADDRESS: '127.0.0.1',
      LLM_PROVIDER: liveOllama || runtimeFailure ? 'ollama' : 'fake',
      ...(runtimeFailure ? {
        OLLAMA_BASE_URL: 'http://127.0.0.1:1', RAG_ENABLED: 'false', RAG_REQUIRE_ON_STARTUP: 'false',
        REDATOR_VERIFY_RUNTIME: 'false', OLLAMA_AUTO_PULL: 'false'
      } : {}),
      REDATOR_LOCAL_API_TOKEN: 'true',
      REDATOR_DATA_DIR: isolatedCollection
    }
  },
  use: {
    baseURL,
    launchOptions: { slowMo: Number(process.env.E2E_SLOW_MO || 0) },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
