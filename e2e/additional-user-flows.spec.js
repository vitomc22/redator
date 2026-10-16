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
  await expect(page.getByText(/até 8 MB/)).toBeVisible();
  const transfer = await page.evaluateHandle(() => {
    const value = new DataTransfer();
    value.items.add(new File(['%PDF-1.4 e2e'], 'redacao.pdf', { type: 'application/pdf' }));
    return value;
  });
  await page.getByLabel(/Selecione seu documento/).dispatchEvent('drop', { dataTransfer: transfer });
  await expect(page.getByText(/redacao\.pdf/)).toBeVisible();
  await expect(page.getByLabel('Prévia de redacao.pdf')).toBeVisible();
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

test('progresso compara reescritas e filtra competências sem misturar IA e humano', async ({ page }) => {
  await baseRoutes(page);
  await page.route('**/api/me/progress', route => route.fulfill({ json: { student: 'pilot-student', points: [
    { essayId: 9400, groupId: 9400, attemptNumber: 1, theme: theme.titulo, text: 'Primeira versão da redação.', total: 760, provider: 'ollama',
      scores: [{ competencia: 'C1', nota: 160 }, { competencia: 'C2', nota: 160 }, { competencia: 'C3', nota: 120 }, { competencia: 'C4', nota: 160 }, { competencia: 'C5', nota: 160 }],
      model: 'gemma4:e2b-it-q4_K_M', human: { total: 720, publishedAt: '2026-10-15T10:00:00Z', scores: { C1: 160, C2: 120, C3: 120, C4: 160, C5: 160 } } },
    { essayId: 9401, groupId: 9400, attemptNumber: 2, theme: theme.titulo, text: 'Última versão com argumento aprofundado.', total: 880, provider: 'ollama',
      scores: [{ competencia: 'C1', nota: 160 }, { competencia: 'C2', nota: 160 }, { competencia: 'C3', nota: 200 }, { competencia: 'C4', nota: 160 }, { competencia: 'C5', nota: 200 }],
      model: 'gemma4:e2b-it-q4_K_M', human: { total: 800, scores: { C1: 160, C2: 160, C3: 160, C4: 160, C5: 160 } } }
  ] } }));
  await page.goto('/#progress');
  await expect(page.getByRole('heading', { name: 'Meu progresso' })).toBeVisible();
  const evolution = page.getByRole('table', { name: 'Evolução nesta redação' });
  await expect(evolution.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('120');
  await expect(evolution.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('200');
  await expect(evolution.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('+80');
  await page.getByText('Comparar textos da primeira e última tentativa').click();
  await expect(page.getByText('Primeira versão da redação.')).toBeVisible();
  await expect(page.getByText('Última versão com argumento aprofundado.')).toBeVisible();
  await page.getByLabel('Filtrar competência').selectOption('C3');
  const history = page.getByRole('table', { name: 'Histórico de progresso' });
  const latest = history.getByRole('row').filter({ hasText: theme.titulo }).nth(1);
  await expect(latest).toContainText('200');
  await expect(latest).toContainText('160');
  await expect(history).toContainText('15/10/2026');
  await expect(page.locator('.chart-bar')).toHaveCount(4);
});

test('runtime indisponível é comunicado sem bloquear a redação', async ({ page }) => {
  await page.route('**/api/runtime', route => route.fulfill({ status: 503, json: { error: 'Runtime indisponível' } }));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
  await page.goto('/#new');
  await expect(page.getByText('IA indisponível')).toBeVisible();
  await expect(page.getByLabel('Texto da redação')).toBeVisible();
});

test('corretor filtra solicitações, correções em andamento e concluídas', async ({ page }) => {
  await baseRoutes(page);
  const rows = {
    REQUESTED: [{ id: 51, attemptNumber: 1, theme: 'Tema solicitado', status: 'REQUESTED', requestedAt: '2026-10-16T10:00:00Z' }],
    IN_REVIEW: [{ id: 52, attemptNumber: 2, theme: 'Tema em correção', status: 'IN_REVIEW', requestedAt: '2026-10-16T10:10:00Z' }],
    COMPLETED: [{ id: 53, attemptNumber: 3, theme: 'Tema concluído', status: 'COMPLETED', requestedAt: '2026-10-16T10:20:00Z' }]
  };
  await page.route(/\/api\/reviewer\/review-requests\?status=(REQUESTED|IN_REVIEW|COMPLETED)$/, route => {
    const status = new URL(route.request().url()).searchParams.get('status');
    return route.fulfill({ json: rows[status] });
  });
  await page.route('**/api/reviewer/review-requests/52', route => route.fulfill({ json: {
    ...rows.IN_REVIEW[0], text, recorte: theme.recorte, motivadores: theme.motivadores,
    draft: { savedAt: '2026-10-16T10:30:00Z', scores: { C1: 160, C2: 120 },
      comments: { C2: 'Repertório ainda pouco integrado.' }, mainGuidance: 'Aprofundar o segundo argumento.' }
  } }));
  await page.goto('/#new');
  await page.goto('/#reviewer');
  await page.getByLabel('Chave local').fill('local-reviewer');
  await page.getByRole('button', { name: 'Carregar correções' }).click();
  await expect(page.getByText('Tema solicitado')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Assumir' })).toBeVisible();
  await page.getByLabel('Estado da fila').selectOption('IN_REVIEW');
  await expect(page.getByText('Tema em correção')).toBeVisible();
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page.getByText(/Rascunho recuperado/)).toBeVisible();
  await expect(page.locator('#score-C1')).toHaveValue('160');
  await expect(page.locator('#score-C2')).toHaveValue('120');
  await expect(page.locator('#comment-C2')).toHaveValue('Repertório ainda pouco integrado.');
  await expect(page.getByLabel('Orientação principal')).toHaveValue('Aprofundar o segundo argumento.');
  await page.goto('/#new');
  await page.goto('/#reviewer');
  await page.getByLabel('Chave local').fill('local-reviewer');
  await page.getByLabel('Estado da fila').selectOption('COMPLETED');
  await expect(page.getByText('Tema concluído')).toBeVisible();
  await expect(page.getByText('Publicada')).toBeVisible();
});
