const { test, expect } = require('@playwright/test');

const theme = {
  id: 3,
  titulo: 'Desafios para o enfrentamento da desinformacao no Brasil',
  recorte: 'Discuta os desafios para enfrentar a desinformacao no Brasil e apresente uma proposta de intervencao.',
  motivadores: 'Educacao midiatica, transparencia das plataformas e liberdade de expressao.'
};
const essayText = [
  'A Constituição Federal assegura o direito à informação, mas a desinformação compromete a cidadania brasileira.',
  'Em primeiro lugar, a falta de educação midiática dificulta a verificação de fontes e favorece o compartilhamento de falsidades.',
  'Além disso, algoritmos orientados pelo engajamento ampliam conteúdos sensacionalistas e fragilizam o debate público.',
  'Portanto, o Ministério da Educação deve promover oficinas de checagem de fatos, e o Congresso deve exigir transparência algorítmica.'
].join('\n\n');

function competence(competencia, nota) {
  return {
    competencia, nota, nivelSugerido: nota, needsReview: false,
    evidencias: ['Trecho pertinente identificado na redação.'],
    problemas: ['Aprofundar a justificativa para avançar um nível.'],
    motivos: ['Pontuação atribuída conforme a rubrica ENEM.']
  };
}

test('fluxo completo: aluno, IA, solicitação, corretor e comparação final', async ({ page }) => {
  let reviewStatus = '';
  let publishedEvaluation = null;
  const essay = {
    id: 9100, tema_id: 3, text: essayText, status: 'READY', attempt_number: 1,
    group_id: 9100, title: 'Demonstração E2E completa', theme, humanReview: {}
  };
  const aiResult = {
    runId: 7100, essayId: 9100, profile: 'normal', status: 'READY', total: 880,
    provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true,
    needsReview: false, limitations: [], reviewReasons: [], competences: {
      C1: competence('C1', 160), C2: competence('C2', 160),
      C3: competence('C3', 200), C4: competence('C4', 160), C5: competence('C5', 200)
    }
  };

  await page.route('**/api/runtime', route => route.fulfill({ json: {
    provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true,
    rag: { enabled: true, available: true, ragEligible: 6579 }
  }}));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
  await page.route('**/api/attempts', route => route.fulfill({ status: 201, json: { essay } }));
  await page.route('**/api/essays/9100/evaluate?profile=normal', async route => {
    await new Promise(resolve => setTimeout(resolve, 2500));
    await route.fulfill({ json: aiResult });
  });
  await page.route('**/api/essays/9100/result', route => route.fulfill({ json: aiResult }));
  await page.route('**/api/essays/9100/human-review-request', route => route.fulfill({ json:
    reviewStatus ? {
      id: 81, essayId: 9100, referenceRunId: 7100, status: reviewStatus,
      ...(publishedEvaluation ? { evaluation: publishedEvaluation } : {})
    } : {}
  }));
  await page.route('**/api/essays/9100/human-review-requests', route => {
    reviewStatus = 'REQUESTED';
    return route.fulfill({ status: 201, json: { id: 81, essayId: 9100, status: reviewStatus } });
  });
  await page.route('**/api/essays/9100', route => route.fulfill({ json: essay }));
  await page.route('**/api/reviewer/review-requests?status=REQUESTED', route => route.fulfill({ json:
    reviewStatus === 'REQUESTED' ? [{
      id: 81, essayId: 9100, attemptNumber: 1, theme: theme.titulo,
      requestedAt: new Date().toISOString(), text: essayText, recorte: theme.recorte,
      motivadores: theme.motivadores, status: 'REQUESTED'
    }] : []
  }));
  await page.route('**/api/reviewer/review-requests/81/claim', route => {
    reviewStatus = 'IN_REVIEW';
    return route.fulfill({ json: { id: 81, essayId: 9100, status: reviewStatus } });
  });
  await page.route('**/api/reviewer/review-requests/81', route => route.fulfill({ json: {
    id: 81, essayId: 9100, status: reviewStatus, text: essayText, attemptNumber: 1,
    theme: theme.titulo, recorte: theme.recorte, motivadores: theme.motivadores
  }}));
  await page.route('**/api/reviewer/review-requests/81/publish', async route => {
    const input = route.request().postDataJSON();
    expect(input.scores).toEqual({ C1: 160, C2: 160, C3: 160, C4: 160, C5: 160 });
    publishedEvaluation = { ...input, total: 800, reviewerId: 'corretor-e2e' };
    reviewStatus = 'COMPLETED';
    await route.fulfill({ json: { requestId: 81, status: 'PUBLISHED', total: 800, scores: input.scores } });
  });

  await page.goto('/#new');
  await expect(page.getByRole('heading', { name: 'Nova redação' })).toBeVisible();
  await page.getByLabel('Tema obrigatório').selectOption('3');
  await page.getByLabel('Título').fill('Demonstração E2E completa');
  await page.getByLabel('Texto da redação').fill(essayText);
  const attemptCreated = page.waitForResponse(response =>
    response.request().method() === 'POST' && response.url().endsWith('/api/attempts'));
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  expect((await attemptCreated).ok()).toBe(true);

  await expect(page.getByRole('heading', { name: 'Tentativa 1' })).toBeVisible();
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();
  await expect(page.getByText('Analisando sua redação pelas cinco competências')).toBeVisible();
  await expect(page.getByText('880 / 1000')).toBeVisible();
  await page.getByRole('button', { name: 'Solicitar correção humana' }).click();
  await expect(page.getByRole('status')).toContainText('Correção humana solicitada.');

  await page.getByRole('button', { name: 'Correções pendentes' }).click();
  await page.getByLabel('Identificador').fill('corretor-e2e');
  await page.getByRole('button', { name: 'Carregar correções' }).click();
  await expect(page.getByText(theme.titulo)).toBeVisible();
  await page.getByRole('button', { name: 'Assumir' }).click();

  await expect(page.getByRole('heading', { name: 'Correção independente' })).toBeVisible();
  for (const c of ['C1', 'C2', 'C3', 'C4', 'C5']) {
    await page.locator(`#score-${c}`).selectOption('160');
    await page.locator(`#comment-${c}`).fill(`Justificativa humana para ${c}.`);
  }
  await page.getByLabel('Orientação principal').fill('Aprofundar dados e integrar melhor o repertório aos argumentos.');
  await page.getByRole('button', { name: 'Publicar' }).click();
  await expect(page.getByRole('status')).toContainText('Correção publicada.');

  await page.goto('/#result/9100');
  await expect(page.getByRole('heading', { name: 'IA × Humano' })).toBeVisible();
  await expect(page.getByText('880 IA · 800 humano')).toBeVisible();
  const comparison = page.getByRole('table', { name: 'Comparação por competência' });
  await expect(comparison).toBeVisible();
  await expect(comparison.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('200');
  await expect(comparison.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('160');
  await expect(comparison.getByRole('row', { name: /C3 — Argumentação/ })).toContainText('Justificativa humana para C3.');
  await expect(page.getByText('Aprofundar dados e integrar melhor o repertório aos argumentos.')).toBeVisible();
  await expect(page.getByText(/não geram média automática/)).toBeVisible();
  await expect(page.locator('.competence')).toHaveCount(5);
  if (process.env.E2E_DEMO === '1') await page.waitForTimeout(8_000);
});
