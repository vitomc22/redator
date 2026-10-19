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
