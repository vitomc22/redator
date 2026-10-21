const { test, expect } = require('@playwright/test');

const theme = {
  id: 3,
  titulo: 'Desafios para o enfrentamento da desinformacao no Brasil',
  recorte: 'Discuta os desafios para enfrentar a desinformacao no Brasil.',
  motivadores: 'Educacao midiatica e transparencia das plataformas.'
};
const essayText = 'A desinformação exige educação midiática e transparência. '.repeat(12);
const scores = Object.fromEntries(['C1', 'C2', 'C3', 'C4', 'C5'].map(c => [c, {
  competencia: c, nota: 160, nivelSugerido: 160,
  evidencias: ['Trecho da redação'], problemas: ['Ponto a aprofundar'],
  motivos: ['Justificativa conforme a rubrica.'], needsReview: false
}]));

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.editorInputEvents = [];
    for (const type of ['pointerdown', 'pointerup', 'click', 'scroll']) {
      document.addEventListener(type, event => {
        const button = document.querySelector('#continue');
        if (!button) return;
        const rect = button.getBoundingClientRect();
        window.editorInputEvents.push({ type, time: performance.now(), target: event.target.id,
          x: event.clientX, y: event.clientY, scrollY, handler: typeof button.onclick,
          button: { x: rect.x, y: rect.y, width: rect.width, height: rect.height } });
      }, true);
    }
  });
});

test.afterEach(async ({ page }, info) => {
  if (info.status !== info.expectedStatus) await info.attach('editor-input-events', {
    body: JSON.stringify(await page.evaluate(() => window.editorInputEvents), null, 2),
    contentType: 'application/json'
  });
});

test('aluno envia texto, confirma e vê a correção completa', async ({ page }) => {
  const essay = {
    id: 9001, tema_id: 3, text: essayText, status: 'READY', attempt_number: 1,
    group_id: 9001, title: 'E2E UI', theme, humanReview: {}
  };
  const result = {
    runId: 7001, essayId: 9001, profile: 'normal', status: 'READY', total: 800,
    provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true,
    needsReview: false, limitations: [], reviewReasons: [], competences: scores
  };

  await page.route('**/api/runtime', route => route.fulfill({ json: {
    provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true
  }}));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
  await page.route('**/api/attempts', async route => {
    const body = route.request().postDataJSON();
    expect(body).toMatchObject({ temaId: 3, title: 'E2E UI', sourceType: 'text' });
    expect(body.text).toContain('desinformação');
    await route.fulfill({ status: 201, json: { essay } });
  });
  await page.route('**/api/essays/9001/evaluate?profile=normal', route => route.fulfill({ json: result }));
  await page.route('**/api/essays/9001/result', route => route.fulfill({ json: result }));
  await page.route('**/api/essays/9001/human-review-request', route => route.fulfill({ json: {} }));
  await page.route('**/api/essays/9001', route => route.fulfill({ json: essay }));

  await page.goto('/#new');
  await expect(page.getByText('ollama · gemma4:e2b-it-q4_K_M')).toBeVisible();
  await page.getByLabel('Tema obrigatório').selectOption('3');
  await expect(page.getByText(theme.recorte)).toBeVisible();
  await page.getByLabel('Título').fill('E2E UI');
  await page.getByLabel('Texto da redação').fill(essayText);
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click({ delay: 100 });

  await expect(page).toHaveURL(/#inspect\/9001$/);
  await expect(page.getByLabel('Texto da tentativa')).toHaveValue(essayText);
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();

  await expect(page).toHaveURL(/#result\/9001$/);
  await expect(page.locator('.competence')).toHaveCount(5);
  await expect(page.getByText('800 / 1000')).toBeVisible();
  await expect(page.getByText('C5 — Proposta de intervenção')).toBeVisible();
  await expect(page.getByText('Avaliação por IA · experimental')).toBeVisible();
});

test('não permite envio sem tema e texto', async ({ page }) => {
  await page.route('**/api/runtime', route => route.fulfill({ json: { provider: 'ollama', model: 'gemma4' } }));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
  await page.goto('/#new');
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page.getByRole('status')).toContainText('Informe o tema e o texto da redação.');
});
