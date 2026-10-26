import { defineConfig, devices } from '@playwright/test';

const baseURL = `http://127.0.0.1:${process.env.TOVER_IDENTITY_BROWSER_PORT || 33310}`;
export default defineConfig({
  outputDir: 'test-results/identity',
  testDir: '.', testMatch: 'identity.spec.mjs', workers: 1, fullyParallel: false,
  timeout: 90_000, reporter: 'list',
  use: { ...devices['Desktop Chrome'], baseURL, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: 'node start-identity-server.mjs', url: `${baseURL}/readyz`, timeout: 120_000, reuseExistingServer: false },
});
