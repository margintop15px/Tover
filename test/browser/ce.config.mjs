import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_BROWSER_PORT || 33301);

export default defineConfig({
  testDir: '.',
  outputDir: 'test-results/ce',
  testMatch: 'ce-migration.spec.mjs',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  forbidOnly: Boolean(process.env.CI),
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `node start-server.mjs production ${port}`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
