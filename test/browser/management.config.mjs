import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_BROWSER_PORT || 33305);
export default defineConfig({
  testDir: '.', testMatch: 'management.spec.mjs', workers: 1, fullyParallel: false,
  timeout: 60_000, reporter: 'list', outputDir: 'test-results/management',
  use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: { command: `node start-server.mjs production ${port}`,
    env: { TOVER_BROWSER_MANAGEMENT_FIXTURES: 'true' },
    url: `http://127.0.0.1:${port}`, reuseExistingServer: false, timeout: 120_000 },
});
