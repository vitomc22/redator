const { defineConfig, devices } = require('@playwright/test');

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
  webServer: {
    command: 'mvn spring-boot:run',
    url: 'http://127.0.0.1:8080/api/runtime',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      ...process.env,
      LLM_PROVIDER: 'fake', // provider exclusivo da suíte; a aplicação normal exige Ollama
      SPRING_DATASOURCE_URL: 'jdbc:sqlite:./target/playwright-redator.db'
    }
  },
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:8080',
    launchOptions: { slowMo: Number(process.env.E2E_SLOW_MO || 0) },
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
