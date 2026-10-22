const { test } = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');

test('the executable package contains its current application and runtime SBOM', () => {
  const artifact = resolve('target/corretor-enem-0.1.0-SNAPSHOT.jar');
  const sbomPath = 'META-INF/sbom/application.cdx.json';
  const entries = execFileSync('jar', ['tf', artifact], { encoding: 'utf8' }).split(/\r?\n/);
  assert.ok(entries.includes(sbomPath), 'The executable JAR must contain the generated SBOM');
  const resources = ['application.properties', 'static/index.html',
    'com/redator/corretor/config/ProfileRoutingDataSource.class',
    'com/redator/corretor/service/ProfileBackupService.class',
    'com/redator/corretor/service/OllamaLlmProvider.class'];
  const directory = mkdtempSync(join(tmpdir(), 'redator-package-'));
  try {
    execFileSync('jar', ['xf', artifact, sbomPath, ...resources.map(r => `BOOT-INF/classes/${r}`)], { cwd: directory });
    const sbom = JSON.parse(readFileSync(join(directory, sbomPath), 'utf8'));
    assert.equal(sbom.bomFormat, 'CycloneDX');
    assert.equal(sbom.specVersion, '1.6');
    assert.ok(sbom.components.some(c => c.name === 'sqlite-jdbc'));
    assert.ok(!sbom.components.some(c => /junit|mockito/.test(c.name)), 'Test libraries must stay out of the runtime SBOM');
    for (const resource of resources) {
      assert.deepEqual(readFileSync(join(directory, 'BOOT-INF/classes', resource)),
        readFileSync(join('target/classes', resource)), `Stale packaged resource: ${resource}`);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
