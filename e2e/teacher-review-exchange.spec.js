const { test, expect } = require('@playwright/test');
const { writeFile } = require('node:fs/promises');

test('estudante e professor trocam correção cifrada de ponta a ponta', async ({ page }) => {
  const suffix = `${Date.now()}-${test.info().workerIndex}`;
  const profile = `student-review-${suffix}`;
  await page.goto('/#new');
  await page.evaluate(user => localStorage.setItem('redator-local-user', user), profile);
  await page.reload();

  const essayId = await page.evaluate(async suffix => {
    const created = await fetch('/api/attempts', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ temaId: 1, title: `Troca ${suffix}`, sourceType: 'text',
        text: 'A desinformação exige educação midiática, transparência algorítmica e participação cidadã.' })
    }).then(response => response.json());
    await fetch(`/api/essays/${created.essay.id}/human-review-requests`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    });
    return created.essay.id;
  }, suffix);

  await page.goto(`/#review-exchange/${essayId}`);
  await expect(page.getByRole('heading', { name: 'Correção com seu professor' })).toBeVisible();
  const requestDownloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar pedido cifrado' }).click();
  const requestDownload = await requestDownloadPromise;
  const requestPath = await requestDownload.path();
  const secret = await page.getByLabel('Segredo do pacote').inputValue();
  expect(secret).toMatch(/^[A-Za-z0-9_-]{43}$/);

  await page.goto('/#reviewer');
  await expect(page.getByRole('heading', { name: 'Corrigir para um estudante' })).toBeVisible();
  await page.getByLabel('Arquivo do estudante').setInputFiles(requestPath);
  await page.getByLabel('Segredo recebido separadamente').fill(secret);
  await page.getByRole('button', { name: 'Abrir redação' }).click();
  await expect(page.getByText('A desinformação exige educação midiática')).toBeVisible();
  await page.getByLabel('Seu nome ou identificador').fill('Professora E2E');
  for (const [competence, score] of Object.entries({ C1: '160', C2: '200', C3: '160', C4: '160', C5: '200' })) {
    await page.getByLabel(new RegExp(`^${competence} —`)).selectOption(score);
  }
  await page.getByLabel('Comentário C3').fill('Aprofunde a relação de causa e consequência.');
  await page.getByLabel('Orientação principal').fill('Integre o repertório diretamente à tese.');
  const responseDownloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar correção cifrada' }).click();
  const responseDownload = await responseDownloadPromise;
  const responsePath = await responseDownload.path();

  await page.goto(`/#review-exchange/${essayId}`);
  await page.getByLabel('Arquivo de resposta').setInputFiles(responsePath);
  await page.getByLabel('Segredo', { exact: true }).fill(secret);
  await page.getByRole('button', { name: 'Importar e publicar correção' }).click();
  await expect(page.locator('#exchangeStatus')).toContainText('880 pontos');
  await expect(page).toHaveURL(new RegExp(`#result/${essayId}$`));

  const persisted = await page.evaluate(id => fetch(`/api/essays/${id}/human-review-request`).then(r => r.json()), essayId);
  expect(persisted.status).toBe('COMPLETED');
  expect(persisted.evaluation.total).toBe(880);
  expect(persisted.evaluation.reviewerId).toBe('Professora E2E');
  await expect(page.getByRole('heading', { name: 'Correção humana publicada' })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Notas da correção humana' })).toContainText('Aprofunde a relação de causa e consequência.');
  await expect(page.getByText('Integre o repertório diretamente à tese.')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Correção humana publicada' })).toBeVisible();
  await page.getByRole('button', { name: 'Diagnóstico e progresso' }).click();
  await expect(page.locator('.chart-bar.human')).toHaveAttribute('title', 'Humano 880');
  await expect(page.locator('.chart-bar:not(.human)')).toHaveCount(0);
  await page.getByLabel('Filtrar competência').selectOption('C3');
  await expect(page.locator('.chart-bar.human')).toHaveAttribute('title', 'Humano 160');
  await page.reload();
  await expect(page.locator('.chart-bar.human')).toHaveAttribute('title', 'Humano 880');
  const progress = await page.evaluate(() => fetch('/api/me/progress').then(response => response.json()));
  expect(progress.points).toHaveLength(1);
  expect(progress.points[0]).toMatchObject({ essayId, total: null, runId: null, provider: null });
  const evidence = test.info().outputPath('human-only-progress.json');
  await writeFile(evidence, JSON.stringify({ essayId, human: persisted, progress }, null, 2));
  await test.info().attach('human-only-progress', { path: evidence, contentType: 'application/json' });
});

test('professor não consegue gerar resposta incompleta', async ({ page }) => {
  await page.goto('/#reviewer');
  await expect(page.getByRole('button', { name: 'Abrir redação' })).toBeVisible();
  await page.evaluate(() => renderTeacherPackageForm({ theme: 'Tema', prompt: 'Recorte', motivators: 'Motivadores',
    essayText: 'Texto autorizado.' }, 'cGFjb3Rl', 'segredo'));
  await page.getByRole('button', { name: 'Baixar correção cifrada' }).click();
  await expect(page.getByText('Preencha as notas C1–C5.')).toBeVisible();
});

test('estudante cancela pedido e revoga pacotes exportados', async ({ page }) => {
  const profile = `student-cancel-${Date.now()}-${test.info().workerIndex}`;
  await page.goto('/#new');
  await page.evaluate(user => localStorage.setItem('redator-local-user', user), profile);
  await page.reload();
  const essayId = await page.evaluate(async () => {
    const created = await fetch('/api/attempts', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ temaId: 1, title: 'Cancelar', sourceType: 'text', text: 'Texto privado para cancelamento.' })
    }).then(response => response.json());
    await fetch(`/api/essays/${created.essay.id}/human-review-requests`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}'
    });
    return created.essay.id;
  });
  await page.goto(`/#review-exchange/${essayId}`);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar pedido cifrado' }).click();
  await downloadPromise;
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Cancelar pedido' }).click();
  await expect(page.getByText('Pedido cancelado e pacotes revogados.')).toBeVisible();
  const state = await page.evaluate(id => fetch(`/api/essays/${id}/human-review-request`).then(r => r.json()), essayId);
  expect(state.status).toBe('CANCELLED');
});
