import { defineConfig, devices } from '@playwright/test';

// Static-asset fixtures own an ephemeral HTTP server; no application or identity store is started.
export default defineConfig({
  testDir: '.',
  outputDir: 'test-results/viewport', testMatch: 'ce-viewport.spec.mjs', fullyParallel: false, workers: 1,
  timeout: 45_000, forbidOnly: Boolean(process.env.CI), reporter: 'list',
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});
