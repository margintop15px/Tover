import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_CATALOGUE_PORT || 33315);
export default defineConfig({
  testDir: '.', testMatch: 'catalogues.spec.mjs', outputDir: 'test-results/catalogues',
  fullyParallel: false, workers: 1, timeout: 60_000, reporter: 'list',
  use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: `node start-server.mjs production ${port}`, url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120_000 },
});
