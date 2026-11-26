import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_MARKETPLACE_PORT || 33316);
export default defineConfig({
  testDir: '.', testMatch: 'marketplace.spec.mjs', outputDir: 'test-results/marketplace',
  fullyParallel: false, workers: 1, timeout: 90_000, reporter: 'list',
  use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: `TOVER_BROWSER_MARKETPLACE_FIXTURES=true node start-server.mjs production ${port}`, url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120_000 },
});
