const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');
const { writeFile } = require('node:fs/promises');

test('falha real de conexão não publica notas e permite correção humana @runtime-failure', async ({ page }) => {
  test.skip(process.env.E2E_RUNTIME_FAILURE !== '1', 'Execute com npm run e2e:failure.');
  await page.addInitScript(user => localStorage.setItem('redator-local-user', user), `failure-${randomUUID()}`);
  await page.goto('/#new');
  await expect(page.getByText('ollama · gemma4:e2b-it-q4_K_M')).toBeVisible();
  const runtime = await page.evaluate(async () => (await fetch('/api/runtime')).json());
  expect(runtime.provider).toBe('ollama');
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByLabel('Título').fill('Falha de transporte real');
  await page.getByLabel('Texto da redação').fill([
    'A educação pública é essencial para reduzir as desigualdades sociais.',
    'A falta de recursos nas escolas e de apoio aos professores limita o acesso ao ensino.',
    'Portanto, o Ministério da Educação deve financiar as escolas e formar docentes,',
    'por meio de metas públicas e ações locais, para garantir esse direito.'
  ].join(' '));
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page).toHaveURL(/#inspect\/\d+$/);
  const id = Number(page.url().split('/').pop());
  const evaluated = page.waitForResponse(response => response.url().includes(`/api/essays/${id}/evaluate`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();
  const response = await evaluated;
  expect(response.status()).toBe(503);
  const failure = await response.json();
  expect(failure.code).toBe('RUNTIME_UNAVAILABLE');
  await expect(page.getByText('RUNTIME_UNAVAILABLE', { exact: true })).toBeVisible();
  await expect(page.locator('.total')).toHaveCount(0);
  await expect(page.locator('.competence')).toHaveCount(0);

  const result = await page.evaluate(async id => (await fetch(`/api/essays/${id}/result`)).json(), id);
  expect(result).toMatchObject({ status: 'FAILED', total: null, failureCode: 'RUNTIME_UNAVAILABLE' });
  expect(Object.keys(result.competences)).toHaveLength(0);
  const run = await page.evaluate(async id => (await fetch(`/api/runs/${id}`)).json(), failure.runId);
  expect(run).toMatchObject({ status: 'FAILED', total: null });
  expect(run.llmCalls).toHaveLength(0);

  await page.getByRole('button', { name: 'Solicitar correção humana' }).click();
  await expect(page.getByRole('status')).toContainText('Correção humana solicitada.');
  const human = await page.evaluate(async id => (await fetch(`/api/essays/${id}/human-review-request`)).json(), id);
  expect(human.status).toBe('REQUESTED');
  await page.reload();
  await expect(page.locator('.total')).toHaveCount(0);
  expect((await page.evaluate(async id => (await fetch(`/api/essays/${id}/human-review-request`)).json(), id)).status).toBe('REQUESTED');
  const evidence = test.info().outputPath('actual-transport-failure.json');
  await writeFile(evidence, JSON.stringify({ httpStatus: response.status(), runtime, failure, result, run, human }, null, 2));
  await test.info().attach('actual-transport-failure', { path: evidence, contentType: 'application/json' });
});
