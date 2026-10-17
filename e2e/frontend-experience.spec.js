const { test, expect } = require('@playwright/test');

const theme = {
  id: 3,
  titulo: 'Desafios para o enfrentamento da desinformação no Brasil',
  recorte: 'Discuta os desafios para enfrentar a desinformação no Brasil.',
  motivadores: 'Educação midiática e transparência das plataformas.'
};

async function mockBootstrap(page) {
  await page.route('**/api/runtime', route => route.fulfill({ json: {
    provider: 'ollama', model: 'gemma4:e2b-it-q4_K_M', experimental: true,
    rag: { enabled: true, available: true, ragEligible: 6579 }
  }}));
  await page.route('**/api/temas', route => route.fulfill({ json: [theme] }));
}

test('novo editor apresenta identidade editorial e métricas em tempo real', async ({ page }) => {
  await mockBootstrap(page);
  await page.goto('/#new');

  await expect(page.getByRole('link', { name: 'Redator ENEM — início' })).toBeVisible();
  await expect(page.getByText('Auditável & pedagógico')).toBeVisible();
  await expect(page.getByText('ollama · gemma4:e2b-it-q4_K_M')).toBeVisible();
  await expect(page.getByText('6579 exemplos prontos')).toBeVisible();

  const editor = page.getByLabel('Texto da redação');
  await expect(editor).toHaveClass(/essay-editor/);
  await editor.fill('Primeiro parágrafo com cinco palavras.\n\nSegundo parágrafo muito curto.');
  await expect(page.locator('.editor-metrics')).toContainText('9 palavras');
  await expect(page.locator('.editor-metrics')).toContainText('2 parágrafos');
});

test('menu móvel abre, navega e fecha após a escolha', async ({ page }) => {
  await mockBootstrap(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/#new');

  const menu = page.getByRole('button', { name: 'Abrir menu' });
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await menu.click();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByRole('button', { name: 'Diagnóstico e progresso' })).toBeVisible();
  await page.getByRole('button', { name: 'Nova redação' }).click();
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#primaryNav')).not.toHaveClass(/open/);
});

test('layout móvel mantém editor e ações dentro da viewport', async ({ page }) => {
  await mockBootstrap(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/#new');

  await page.getByLabel('Tema obrigatório').selectOption('3');
  await expect(page.getByText(theme.recorte)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continuar para inspeção' })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
  const editorBox = await page.getByLabel('Texto da redação').boundingBox();
  expect(editorBox.x).toBeGreaterThanOrEqual(0);
  expect(editorBox.x + editorBox.width).toBeLessThanOrEqual(360);
});
