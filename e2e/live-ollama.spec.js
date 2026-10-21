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

  await expect(page).toHaveURL(/#result\/\d+$/, { timeout: 620_000 });
  await expect(page.locator('.competence')).toHaveCount(5);
  const essayId = page.url().split('/').pop();
  const response = await page.request.get(`/api/essays/${essayId}/result`);
  expect(response.ok()).toBe(true);
  const result = await response.json();
  expect(['READY', 'NEEDS_REVIEW']).toContain(result.status);
  expect(result.provider).toBe('ollama');
  const competences = Object.values(result.competences);
  expect(competences).toHaveLength(5);
  for (const competence of competences) {
    expect([0, 40, 80, 120, 160, 200]).toContain(competence.nota);
    const passages = Array.isArray(competence.evidencias)
      ? competence.evidencias : String(competence.evidencias || '').split(' | ').filter(Boolean);
    for (const evidence of passages) expect(fixture.essay).toContain(evidence);
  }
  if (result.status === 'READY') {
    const total = competences.reduce((sum, competence) => sum + competence.nota, 0);
    expect(result.total).toBe(total);
    await expect(page.locator('.total')).toHaveText(`${total} / 1000`);
  } else {
    expect(result.total).toBeNull();
    await expect(page.locator('.total')).toHaveCount(0);
    await expect(page.getByText('Esta redação precisa de revisão.')).toBeVisible();
  }
  const persisted = await page.request.get(`/api/runs/${result.runId}`);
  expect(persisted.ok()).toBe(true);
  const run = await persisted.json();
  expect(run.llmCalls).toHaveLength(1);
  expect(run.llmCalls[0].cacheHit).toBe(0);
  const audit = JSON.parse(run.llmCalls[0].auditJson);
  expect(audit.modelDigest).toMatch(/^[a-f0-9]{64}$/);
  expect(audit.requestMessagesHash).toMatch(/^[a-f0-9]{64}$/);
  expect(audit.ragEnabled).toBe(true);
  expect(audit.ragExampleCount).toBeGreaterThan(0);
  expect(audit.ragPolicy).toBe(process.env.RAG_POLICY || 'lexical-v1');
  const referenceQuality = {
    referenceSource: fixture.source, expectedTotal: fixture.expected.total, observedTotal: result.total,
    status: result.status, absoluteError: result.total === null ? null : Math.abs(result.total - fixture.expected.total),
    historicalTolerance: 80, runId: result.runId, policy: audit.ragPolicy, modelDigest: audit.modelDigest
  };
  await test.info().attach('human-reference-quality', {
    body: JSON.stringify(referenceQuality, null, 2), contentType: 'application/json'
  });
  if (process.env.E2E_CHECK_REFERENCE_SCORE === '1') {
    expect(result.status).toBe('READY');
    expect(referenceQuality.absoluteError).toBeLessThanOrEqual(80);
  }
});
