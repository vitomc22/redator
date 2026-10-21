const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');
const { deflateSync } = require('node:zlib');
const { writeFile } = require('node:fs/promises');

const paragraphs = [
  'A educacao publica e um direito e reduz as diferencas sociais.',
  'No Brasil, muitas escolas ainda nao contam com livros e salas seguras.',
  'O apoio aos docentes e a oferta de merenda ajudam o aluno a aprender.',
  'A falta de verbas limita o acesso ao ensino e amplia a evasao escolar.',
  'Por isso, o governo deve ampliar o apoio as escolas e formar professores.',
  'As metas devem ser claras e revistas em cada ano com a comunidade.'
];

// A compressed text stream makes the plain-text PDF fallback insufficient for this fixture.
function realPdf(lines) {
  const escaped = value => value.replace(/[\\()]/g, '\\$&');
  const stream = deflateSync(Buffer.from(`BT /F1 12 Tf 16 TL 45 790 Td\n${lines.map(line => `(${escaped(line)}) Tj T*`).join('\n')}\nET`, 'ascii'));
  const bodies = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    Buffer.concat([Buffer.from(`<< /Length ${stream.length} /Filter /FlateDecode >>\nstream\n`), stream, Buffer.from('\nendstream')]),
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  ];
  const parts = [Buffer.from('%PDF-1.4\n')], offsets = [0];
  let position = parts[0].length;
  bodies.forEach((body, index) => {
    offsets.push(position);
    const object = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`), Buffer.isBuffer(body) ? body : Buffer.from(body), Buffer.from('\nendobj\n')]);
    parts.push(object); position += object.length;
  });
  parts.push(Buffer.from(`xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${position}\n%%EOF\n`));
  return Buffer.concat(parts);
}

async function api(page, url, options) {
  return page.evaluate(async ({ url, options }) => {
    const response = await fetch(url, options);
    return { status: response.status, body: await response.json() };
  }, { url, options });
}

test.beforeEach(async ({ page }) => {
  const profile = `pdf-${randomUUID()}`;
  await page.addInitScript(user => localStorage.setItem('redator-local-user', user), profile);
  await page.goto('/#new');
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByRole('button', { name: 'Enviar PDF' }).click();
});

test('PDF real é extraído, conferido e preservado no SQLite antes de avaliar', async ({ page }) => {
  const buffer = realPdf(paragraphs);
  expect(buffer.includes(Buffer.from(paragraphs[0]))).toBe(false);
  await page.locator('#file').setInputFiles({ name: 'redacao-integracao.pdf', mimeType: 'application/pdf', buffer });
  await page.getByLabel('Título').fill('PDF com extração e conferência reais');
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page).toHaveURL(/#inspect\/\d+$/);
  const id = Number(page.url().split('/').pop());
  const transcript = page.getByLabel('Confira a transcrição');
  await expect(transcript).toContainText(paragraphs[0]);
  const original = await transcript.inputValue();
  expect(original).toContain(paragraphs.at(-1));
  const uploaded = await api(page, `/api/essays/${id}`);
  expect(uploaded.body).toMatchObject({ source_type: 'pdf', status: 'NEEDS_REVIEW', text: original });
  expect(uploaded.body.approved_at).toBeNull();

  const blocked = await api(page, `/api/essays/${id}/evaluate?profile=normal`, { method: 'POST' });
  expect(blocked.status).toBe(409);
  expect(blocked.body.code).toBe('DOCUMENT_REVIEW_REQUIRED');
  await page.getByRole('button', { name: 'Confirmar texto e avaliar' }).click();
  await expect(page.getByRole('status')).toContainText('Confirme que revisou a transcrição.');
  expect((await api(page, `/api/essays/${id}/result`)).body.status).toBe('NOT_EVALUATED');

  const corrected = original.replace('ajudam o aluno', 'ajudam os alunos');
  expect(corrected).not.toBe(original);
  await transcript.fill(corrected);
  await page.getByLabel(/Conferi: o texto corresponde/).check();
  await page.getByRole('button', { name: 'Confirmar texto e avaliar' }).click();
  await expect(page).toHaveURL(new RegExp(`#result/${id}$`));
  await expect(page.locator('.competence')).toHaveCount(5);
  await expect(page.locator('.total')).toHaveCount(0);
  const saved = await api(page, `/api/essays/${id}`);
  expect(saved.body.text).toBe(corrected);
  expect(saved.body.approved_at).toBeTruthy();
  expect(saved.body.frozen).toBe(1);
  const result = await api(page, `/api/essays/${id}/result`);
  expect(result.body).toMatchObject({ provider: 'fake', status: 'NEEDS_REVIEW', total: null });

  const immutable = await api(page, `/api/essays/${id}/review`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reviewedText: `${corrected} Nova versão.`, approved: true })
  });
  expect(immutable.status).toBe(409);
  await page.reload();
  expect((await api(page, `/api/essays/${id}`)).body.text).toBe(corrected);
  await page.getByRole('button', { name: 'Solicitar correção humana' }).click();
  await expect(page.getByRole('status')).toContainText('Correção humana solicitada.');
  const requested = (await api(page, `/api/essays/${id}/human-review-request`)).body;
  expect(requested.status).toBe('REQUESTED');
  const reviewer = `reviewer-${randomUUID()}`;
  await page.getByRole('button', { name: 'Correções pendentes' }).click();
  await page.getByLabel('Identificador', { exact: true }).fill(reviewer);
  await page.getByRole('button', { name: 'Carregar correções' }).click();
  await page.locator(`[data-claim="${requested.id}"]`).click();
  await expect(page.getByRole('heading', { name: 'Correção independente' })).toBeVisible();
  await expect(page.locator('.review-text')).toContainText('ajudam os alunos');
  for (const code of ['C1', 'C2', 'C3', 'C4', 'C5']) {
    await page.locator(`#score-${code}`).selectOption('160');
    await page.locator(`#comment-${code}`).fill(`Comentário humano ${code}.`);
  }
  await page.getByLabel('Orientação principal').fill('Revise a ligação entre os argumentos.');
  await page.getByRole('button', { name: 'Salvar rascunho' }).click();
  await expect(page.getByRole('status')).toContainText('Rascunho salvo.');
  expect((await api(page, '/api/me/progress')).body.points).toEqual([]);
  await page.reload();
  await page.getByLabel('Identificador', { exact: true }).fill(reviewer);
  await page.getByLabel('Estado da fila').selectOption('IN_REVIEW');
  await page.getByRole('button', { name: 'Carregar correções' }).click();
  await page.locator(`[data-continue="${requested.id}"]`).click();
  await expect(page.locator('#score-C3')).toHaveValue('160');
  await expect(page.locator('#comment-C3')).toHaveValue('Comentário humano C3.');
  await page.getByRole('button', { name: 'Publicar', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Correção publicada.');
  await page.goto(`/#result/${id}`);
  await expect(page.getByRole('heading', { name: 'Correção humana publicada' })).toBeVisible();
  await expect(page.getByRole('table', { name: 'Notas da correção humana' })).toContainText('Comentário humano C3.');
  await expect(page.locator('.total')).toHaveCount(0);
  await page.getByRole('button', { name: 'Diagnóstico e progresso' }).click();
  await expect(page.locator('.chart-bar.human')).toHaveAttribute('title', 'Humano 800');
  await expect(page.locator('.chart-bar:not(.human)')).toHaveCount(0);
  const evidence = test.info().outputPath('pdf-local-teacher.json');
  await writeFile(evidence, JSON.stringify({
    fixtureScoresAreWorkflowTestData: true,
    uploaded: uploaded.body, approved: saved.body, unreviewedEvaluationStatus: blocked.status,
    frozenEditStatus: immutable.status, fakeResult: result.body,
    publishedResult: (await api(page, `/api/essays/${id}/result`)).body,
    progress: (await api(page, '/api/me/progress')).body
  }, null, 2));
  await test.info().attach('pdf-local-teacher', { path: evidence, contentType: 'application/json' });
});

test('arquivo PDF inválido é recusado sem criar uma tentativa', async ({ page }) => {
  await page.locator('#file').setInputFiles({ name: 'invalido.pdf', mimeType: 'application/pdf', buffer: Buffer.from('Este arquivo nao e um PDF.') });
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page.getByRole('status')).toContainText(/PDF inv[aá]lido/);
  await expect(page).toHaveURL(/#new$/);
  const history = await api(page, '/api/me/essays');
  expect(history.status).toBe(200);
  expect(history.body).toEqual([]);
});
