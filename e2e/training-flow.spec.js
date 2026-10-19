const { test, expect } = require('@playwright/test');

const essayText = [
  'A Constituição Federal garante o direito à informação, mas a desinformação compromete a cidadania brasileira e exige respostas coordenadas.',
  'Em primeiro lugar, a educação midiática insuficiente dificulta a verificação de fontes. Muitos cidadãos compartilham conteúdos sem conferir autoria, data e evidências, ampliando boatos.',
  'Além disso, plataformas orientadas por engajamento favorecem mensagens sensacionalistas. Essa lógica cria bolhas informacionais e enfraquece o debate público democrático.',
  'Portanto, o Ministério da Educação deve promover oficinas de checagem nas escolas, com professores capacitados, enquanto as plataformas devem publicar relatórios de transparência algorítmica, a fim de reduzir a circulação de falsidades.'
].join('\n\n');

test('usuário cria cartões da correção e conclui uma revisão espaçada', async ({ page }) => {
  const profile = `training-${Date.now()}-${test.info().workerIndex}`;
  await page.goto('/#new');
  await page.evaluate(user => localStorage.setItem('redator-local-user', user), profile);
  await page.reload();
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByLabel('Título').fill('Treino espaçado E2E');
  await page.getByLabel('Texto da redação').fill(essayText);
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();
  await expect(page).toHaveURL(/#result\/\d+$/);
  const essayId = Number(page.url().match(/#result\/(\d+)$/)[1]);
  await page.getByRole('button', { name: 'Solicitar correção humana' }).click();
  await page.evaluate(async id => {
    const localUser = localStorage.getItem('redator-local-user');
    const headers = { 'Content-Type': 'application/json', 'X-Local-User': localUser };
    const json = async response => {
      const body = await response.json();
      if (!response.ok) throw new Error(`${response.status}: ${JSON.stringify(body)}`);
      return body;
    };
    const request = await fetch(`/api/essays/${id}/review-package`, {
      method: 'POST', headers, body: '{}'
    }).then(json);
    const responsePackage = await fetch('/api/review-packages/respond', {
      method: 'POST', headers,
      body: JSON.stringify({ packageBase64: request.packageBase64, secret: request.secret,
        reviewer: 'Professora do treino',
        scores: { C1: 160, C2: 160, C3: 160, C4: 160, C5: 160 },
        comments: { C1: 'Revise este ponto.', C2: 'Revise este ponto.', C3: 'Revise este ponto.',
          C4: 'Revise este ponto.', C5: 'Revise este ponto.' },
        mainGuidance: 'Aprofunde argumentos e revise a proposta.' })
    }).then(json);
    await fetch('/api/review-packages/import', {
      method: 'POST', headers,
      body: JSON.stringify({ packageBase64: responsePackage.packageBase64, secret: request.secret })
    }).then(json);
  }, essayId);
  await page.reload();
  await expect(page).toHaveURL(new RegExp(`#result/${essayId}$`));
  await expect(page.getByRole('button', { name: 'Criar cartões de treino' })).toBeVisible();

  await page.getByRole('button', { name: 'Criar cartões de treino' }).click();
  await expect(page.getByRole('heading', { name: 'Treino espaçado' })).toBeVisible();
  await expect(page.getByText('5 cartão(ões) para revisar agora.')).toBeVisible();
  await page.getByLabel('Meta diária').fill('1');
  await page.getByRole('button', { name: 'Salvar meta' }).click();
  await expect(page.getByText('Meta diária: 1 revisão(ões).')).toBeVisible();
  await page.getByRole('button', { name: 'Mostrar resposta' }).click();
  await expect(page.getByText('Ponto para revisar')).toBeVisible();
  await page.getByRole('button', { name: 'Fácil' }).click();
  await expect(page.getByText('Próxima revisão em 1 dia(s).')).toBeVisible();
  await expect(page.getByText('4 cartão(ões) para revisar agora.')).toBeVisible();
  await page.getByRole('button', { name: 'Encerrar sessão' }).click();
  await expect(page.getByText('Meta de hoje alcançada.')).toBeVisible();
  await expect(page.getByText('1 sessão(ões) concluída(s).')).toBeVisible();

  await page.reload();
  await expect(page.getByText('4 cartão(ões) para revisar agora.')).toBeVisible();
});
