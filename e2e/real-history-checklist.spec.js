const { test, expect } = require('@playwright/test');

const essayText = [
  'A Constituição Federal assegura o direito à informação, mas a desinformação prejudica a cidadania brasileira.',
  'Primeiramente, a educação midiática insuficiente reduz a capacidade de verificar fontes e favorece o compartilhamento impensado.',
  'Além disso, algoritmos orientados pelo engajamento ampliam conteúdos sensacionalistas e enfraquecem o debate público.',
  'Portanto, o Ministério da Educação deve promover oficinas de checagem nas escolas, com professores capacitados, para formar cidadãos críticos.'
].join('\n\n');

test('histórico, checklist e reescrita persistem no backend e SQLite reais', async ({ page }) => {
  const suffix = `${Date.now()}-${test.info().workerIndex}`;
  const profile = `history-${suffix}`;
  const firstTitle = `Primeira versão ${suffix}`;
  const rewrittenTitle = `Reescrita ${suffix}`;

  await page.goto('/#new');
  await page.evaluate(user => localStorage.setItem('redator-local-user', user), profile);
  await page.reload();
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByLabel('Título').fill(firstTitle);
  await page.getByLabel('Texto da redação').fill(essayText);
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await page.getByRole('button', { name: 'Avaliar com IA' }).click();
  await expect(page).toHaveURL(/#result\/\d+$/);
  const firstId = Number(page.url().match(/#result\/(\d+)$/)[1]);

  await page.getByRole('button', { name: 'Quero melhorar minha redação' }).click();
  await expect(page.getByRole('heading', { name: 'Plano de melhoria' })).toBeVisible();
  const details = page.locator('details.competence').first();
  await details.locator('summary').click();
  const checklist = details.getByLabel('Revisei este ponto (marcação pessoal)');
  await checklist.check();
  await expect(page.getByText('Checklist atualizado.')).toBeVisible();
  await page.reload();
  const restoredDetails = page.locator('details.competence').first();
  await restoredDetails.locator('summary').click();
  await expect(restoredDetails.getByLabel('Revisei este ponto (marcação pessoal)')).toBeChecked();

  await page.getByRole('button', { name: 'Reescrever e enviar novamente' }).click();
  await expect(page).toHaveURL(new RegExp(`#new\\?parent=${firstId}$`));
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByLabel('Título').fill(rewrittenTitle);
  await page.getByLabel('Texto da redação').fill(`${essayText}\n\nA medida será acompanhada por avaliações semestrais.`);
  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page).toHaveURL(/#inspect\/\d+$/);

  await page.getByRole('button', { name: 'Minhas redações' }).click();
  await expect(page.locator('.attempt-no')).toHaveText('2');
  await page.getByRole('button', { name: 'Abrir' }).click();
  await expect(page.getByText(firstTitle)).toBeVisible();
  await expect(page.getByText(rewrittenTitle)).toBeVisible();
  await expect(page.locator('.attempt')).toHaveCount(2);
});
