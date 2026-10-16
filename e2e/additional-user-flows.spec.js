const { test, expect } = require('@playwright/test');

const theme = {
  id: 3,
  titulo: 'Desafios para o enfrentamento da desinformacao no Brasil',
  recorte: 'Discuta os desafios para enfrentar a desinformacao no Brasil.',
  motivadores: 'Educacao midiatica e transparencia das plataformas.'
};
const text = 'Texto argumentativo conferido pelo estudante sobre desinformação e cidadania. '.repeat(10);

function score(c, nota = 160) {
  return { competencia: c, nota, nivelSugerido: nota, evidencias: ['Trecho válido.'], problemas: ['Aprofundar.'], motivos: ['Rubrica aplicada.'], needsReview: false };
}
const result = {
  runId: 7200, essayId: 9200, profile: 'normal', status: 'READY', total: 800,
  provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true,
  competences: Object.fromEntries(['C1', 'C2', 'C3', 'C4', 'C5'].map(c => [c, score(c)])),
  limitations: [], reviewReasons: []
};

async function baseRoutes(page, runtime = { provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M' }) {
  await page.route('**/api/runtime', route => route.fulfill({ json: runtime }));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
}

test('upload de documento exige conferência antes da avaliação', async ({ page }) => {
  await baseRoutes(page);
  let essay = { id: 9200, tema_id: 3, text, status: 'NEEDS_REVIEW', source_type: 'pdf', attempt_number: 1, group_id: 9200, theme, humanReview: {} };
  await page.route('**/api/attempts', async route => {
    expect(route.request().headers()['content-type']).toContain('multipart/form-data');
    await route.fulfill({ status: 201, json: { essay } });
  });
  await page.route('**/api/essays/9200/review', route => {
    essay = { ...essay, status: 'READY' };
    return route.fulfill({ json: { essayId: 9200, status: 'READY', approved: true, qualityGatePassed: true, text } });
  });
  await page.route('**/api/essays/9200/evaluate?profile=normal', route => route.fulfill({ json: result }));
  await page.route('**/api/essays/9200/result', route => route.fulfill({ json: result }));
  await page.route('**/api/essays/9200/human-review-request', route => route.fulfill({ json: {} }));
  await page.route('**/api/essays/9200', route => route.fulfill({ json: essay }));

  await page.goto('/#new');
  await page.getByLabel('Tema obrigatório').selectOption('3');
  await page.getByRole('button', { name: 'Enviar PDF' }).click();
  await page.getByLabel(/Selecione seu documento/).setInputFiles({ name: 'redacao.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 e2e') });
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page.getByText('Conferência necessária')).toBeVisible();
  await page.getByLabel('Confira a transcrição').fill(text);
  await page.getByLabel(/Conferi: o texto corresponde/).check();
  await page.getByRole('button', { name: 'Confirmar texto e avaliar' }).click();
  await expect(page.getByText('800 / 1000')).toBeVisible();
});

test('falha de inferência não publica nota e permite pedir humano', async ({ page }) => {
  await baseRoutes(page);
  const essay = { id: 9201, tema_id: 3, text, status: 'READY', attempt_number: 1, group_id: 9201, theme, humanReview: {} };
  let requested = false;
  await page.route('**/api/essays/9201/evaluate?profile=normal', route => route.fulfill({
    status: 504, json: { status: 504, error: 'Ollama excedeu o prazo; sem nota.', code: 'INFERENCE_TIMEOUT', runId: 7201 }
  }));
  await page.route('**/api/essays/9201/human-review-requests', route => { requested = true; return route.fulfill({ status: 201, json: { id: 82, status: 'REQUESTED' } }); });
  await page.route('**/api/essays/9201', route => route.fulfill({ json: essay }));
  await page.goto('/#inspect/9201');
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();
  await expect(page.getByText('INFERENCE_TIMEOUT')).toBeVisible();
  await expect(page.locator('.total')).toHaveCount(0);
  await page.getByRole('button', { name: 'Solicitar correção humana' }).click();
  await expect.poll(() => requested).toBe(true);
});

test('histórico agrupa tentativas e inicia reescrita sem sobrescrever', async ({ page }) => {
  await baseRoutes(page);
  await page.route('**/api/me/essays', route => route.fulfill({ json: [{
    groupId: 9300, theme: theme.titulo, attempts: 2, lastActivity: '2026-10-16T10:00:00Z',
    latestEssayId: 9301, latestAiTotal: 840, latestHumanTotal: 800
  }] }));
  await page.route('**/api/me/essays/9300/attempts', route => route.fulfill({ json: [
    { id: 9300, groupId: 9300, attemptNumber: 1, title: 'Primeira versão', theme: theme.titulo, status: 'READY', aiTotal: 760, humanTotal: 760 },
    { id: 9301, groupId: 9300, attemptNumber: 2, title: 'Versão revisada', theme: theme.titulo, status: 'READY', aiTotal: 840, humanTotal: 800 }
  ] }));
  await page.goto('/#essays');
  await expect(page.getByText('IA 840 · Humano 800')).toBeVisible();
  await page.getByRole('button', { name: 'Abrir' }).click();
  await expect(page.getByText('Primeira versão')).toBeVisible();
  await expect(page.getByText('Versão revisada')).toBeVisible();
  await page.getByRole('button', { name: 'Reescrever' }).click();
  await expect(page).toHaveURL(/#new\?parent=9301$/);
  await expect(page.getByRole('heading', { name: 'Reescrever redação' })).toBeVisible();
});

test('plano de melhoria permite checklist pessoal persistido', async ({ page }) => {
  await baseRoutes(page);
  const essay = { id: 9200, tema_id: 3, text, status: 'READY', attempt_number: 1, group_id: 9200, theme, humanReview: {} };
  let saved = null;
  await page.route('**/api/essays/9200/result', route => route.fulfill({ json: result }));
  await page.route('**/api/essays/9200/checklist', async route => {
    if (route.request().method() === 'PUT') { saved = route.request().postDataJSON(); return route.fulfill({ json: saved }); }
    return route.fulfill({ json: [] });
  });
  await page.route('**/api/essays/9200', route => route.fulfill({ json: essay }));
  await page.goto('/#improve/9200');
  await expect(page.getByRole('heading', { name: 'Plano de melhoria' })).toBeVisible();
  await page.locator('details.competence').first().locator('summary').click();
  const checkbox = page.locator('details.competence').first().getByLabel('Revisei este ponto (marcação pessoal)');
  await checkbox.check();
  await expect.poll(() => saved?.checked).toBe(true);
  expect(saved).toMatchObject({ competence: 'C1', itemKey: 'reviewed', checked: true });
});

test('progresso mantém notas de IA e humano separadas', async ({ page }) => {
  await baseRoutes(page);
  await page.route('**/api/me/progress', route => route.fulfill({ json: { student: 'pilot-student', points: [{
    essayId: 9400, groupId: 9400, attemptNumber: 1, theme: theme.titulo,
    total: 880, model: 'gemma4:e2b-it-q4_K_M', human: { total: 800 }
  }] } }));
  await page.goto('/#progress');
  await expect(page.getByRole('heading', { name: 'Meu progresso' })).toBeVisible();
  const row = page.getByRole('row').filter({ hasText: theme.titulo });
  await expect(row).toContainText('880');
  await expect(row).toContainText('800');
  await expect(row).toContainText('gemma4:e2b-it-q4_K_M');
  await expect(page.locator('.chart-bar')).toHaveCount(2);
});

test('runtime indisponível é comunicado sem bloquear a redação', async ({ page }) => {
  await page.route('**/api/runtime', route => route.fulfill({ status: 503, json: { error: 'Runtime indisponível' } }));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
  await page.goto('/#new');
  await expect(page.getByText('IA indisponível')).toBeVisible();
  await expect(page.getByLabel('Texto da redação')).toBeVisible();
});
