const { test, expect } = require('@playwright/test');
const fixture = require('../eval/e2e-desinformacao-800.json');

test('resultado real mostra IA e referência humana independentes @live', async ({ page }) => {
  const essayId = process.env.E2E_LIVE_ESSAY_ID;
  test.skip(!essayId, 'Defina E2E_LIVE_ESSAY_ID para um caso real já corrigido.');

  await page.goto(`/#result/${essayId}`);
  await expect(page.getByText('Avaliação por IA · experimental')).toBeVisible();
  await expect(page.locator('.competence')).toHaveCount(5);
  await expect(page.getByRole('heading', { name: 'IA × Humano' })).toBeVisible();
  await expect(page.getByText(/\d+ IA · 800 humano/)).toBeVisible();

  const diagnostic = page.locator('details.diagnostic');
  await diagnostic.locator('summary').click();
  await expect(diagnostic).toContainText('ollama');
  await expect(diagnostic).toContainText('gemma4:e2b-it-q4_K_M');
});

test('fluxo completo dispara uma correção real no Ollama @live', async ({ page }) => {
  test.skip(process.env.E2E_LIVE_OLLAMA !== '1', 'Teste caro: execute com npm run e2e:live.');
  test.setTimeout(660_000);

  await page.goto('/#new');
  await expect(page.getByText('ollama · gemma4:e2b-it-q4_K_M')).toBeVisible();
  await page.getByLabel('Tema obrigatório').selectOption(String(fixture.themeId));
  await page.getByLabel('Título').fill(`Playwright live ${Date.now()}`);
  await page.getByLabel('Texto da redação').fill(fixture.essay);
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page.getByRole('button', { name: 'Avaliar com IA' })).toBeVisible();
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();

  await expect(page.getByText(/\d+ \/ 1000/)).toBeVisible({ timeout: 620_000 });
  const displayed = Number((await page.locator('.total').innerText()).match(/\d+/)[0]);
  expect(Math.abs(displayed - fixture.expected.total)).toBeLessThanOrEqual(80);
  await expect(page.locator('.competence')).toHaveCount(5);
});
