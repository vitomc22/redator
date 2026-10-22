const { test, expect } = require('@playwright/test');

test('usuário exporta e prepara restauração da coleção SQLite real', async ({ page }) => {
  await page.goto('/#data');
  await expect(page.getByRole('heading', { name: 'Dados locais' })).toBeVisible();

  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar backup completo' }).click();
  const download = await downloadPromise;
  const backupPath = await download.path();
  expect(download.suggestedFilename()).toMatch(/^redator-.*\.sqlite$/);
  expect(backupPath).toBeTruthy();

  await page.getByLabel('Backup SQLite').setInputFiles(backupPath);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Validar e preparar restauração' }).click();

  await expect(page.locator('#restoreStatus')).toContainText('Restauração validada');
  await expect(page.getByText('Restauração preparada com segurança.')).toBeVisible();
});

test('backup privado recusa outro perfil mesmo com IDs de redação iguais', async ({ page }) => {
  const suffix = `${Date.now()}-${test.info().workerIndex}`;
  const alice = `backup-alice-${suffix}`, bob = `backup-bob-${suffix}`;
  async function switchTo(profile, text) {
    await page.evaluate(user => localStorage.setItem('redator-local-user', user), profile);
    await page.reload();
    return page.evaluate(async text => {
      const response = await fetch('/api/attempts', { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ temaId: 1, title: 'Privada', text, sourceType: 'text' }) });
      if (!response.ok) throw new Error(`Criação falhou: ${response.status}`);
      return (await response.json()).essay.id;
    }, text);
  }
  await page.goto('/#data');
  const aliceId = await switchTo(alice, 'Histórico privado de Alice.');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Baixar backup completo' }).click();
  const backupPath = await (await downloadPromise).path();

  const bobId = await switchTo(bob, 'Histórico privado de Bob.');
  expect(aliceId).toBe(1);
  expect(bobId).toBe(1);
  await page.getByLabel('Backup SQLite').setInputFiles(backupPath);
  page.once('dialog', dialog => dialog.accept());
  const rejectedPromise = page.waitForResponse(r => r.url().endsWith('/api/local-data/restore'));
  await page.getByRole('button', { name: 'Validar e preparar restauração' }).click();
  expect((await rejectedPromise).status()).toBe(400);
  await expect(page.locator('#restoreStatus')).toContainText('A restauração não foi preparada.');
  const bobEssay = await page.evaluate(id => fetch(`/api/essays/${id}`).then(r => r.json()), bobId);
  expect(bobEssay.text).toBe('Histórico privado de Bob.');

  await page.evaluate(user => localStorage.setItem('redator-local-user', user), alice);
  await page.reload();
  await page.getByLabel('Backup SQLite').setInputFiles(backupPath);
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Validar e preparar restauração' }).click();
  await expect(page.locator('#restoreStatus')).toContainText('Restauração validada');
});
