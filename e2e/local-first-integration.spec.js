const { test, expect } = require('@playwright/test');

test('backend real isola histórico e rascunho entre perfis locais', async ({ page }) => {
  const suffix = `${Date.now()}-${test.info().workerIndex}`;
  const alice = `alice-${suffix}`;
  const bob = `bob-${suffix}`;
  const privateDraft = `Rascunho privado ${suffix}`;

  await page.goto('/#new');
  await page.evaluate(user => localStorage.setItem('redator-local-user', user), alice);
  await page.reload();
  await page.getByLabel('Tema obrigatório').selectOption({ index: 1 });
  await page.getByLabel('Título').fill(`Integração ${suffix}`);
  await page.getByLabel('Texto da redação').fill(privateDraft);
  await expect.poll(() => page.evaluate(key => localStorage.getItem(`redator-draft:${key}`), alice))
    .toContain(privateDraft);

  page.once('dialog', dialog => dialog.accept(bob));
  await page.locator('.avatar').click();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByLabel('Texto da redação')).toHaveValue('');

  page.once('dialog', dialog => dialog.accept(alice));
  await page.locator('.avatar').click();
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByLabel('Texto da redação')).toHaveValue(privateDraft);

  await page.getByRole('button', { name: 'Continuar para inspeção' }).click();
  await expect(page).toHaveURL(/#inspect\/\d+$/);
  const essayUrl = page.url();

  page.once('dialog', dialog => dialog.accept(bob));
  await page.locator('.avatar').click();
  await page.waitForLoadState('domcontentloaded');
  await page.goto(essayUrl);
  await expect(page.getByRole('heading', { name: 'Não foi possível carregar' })).toBeVisible();
});
