import { defineConfig, devices } from '@playwright/test';
const port = Number(process.env.TOVER_VIEWPORT_PORT || 33311);
export default defineConfig({
  testDir: '.',
  outputDir: 'test-results/viewport-app', testMatch: 'ce-viewport-app.spec.mjs', fullyParallel: false, workers: 1,
  timeout: 60_000, forbidOnly: Boolean(process.env.CI), reporter: 'list',
  use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: `node start-viewport-server.mjs ${port}`, url: `http://127.0.0.1:${port}/readyz`, reuseExistingServer: false, timeout: 120_000 },
});
