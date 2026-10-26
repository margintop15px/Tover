import { defineConfig, devices } from '@playwright/test';
const port = Number(process.env.TOVER_BROWSER_PORT || 33303);
export default defineConfig({
  testDir: '.', testMatch: ['i18n.spec.mjs', 'i18n-workflows.spec.mjs'], workers: 1, fullyParallel: false, timeout: 90_000,
  reporter: 'list', outputDir: 'test-results/i18n',
  use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: `node start-server.mjs production ${port}`, url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120_000 },
});
