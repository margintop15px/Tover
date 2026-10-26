import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_BROWSER_PORT || 33301);
const modes = ['production', 'development'];

export default defineConfig({
  testDir: '.',
  outputDir: 'test-results/application',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: modes.map((mode, index) => ({
    name: mode,
    testMatch: mode === 'production' ? ['production.spec.mjs', 'navigation.spec.mjs', 'smart-workflow.spec.mjs', 'ce-migration.spec.mjs', 'ce-adapter.spec.mjs', 'ce-concurrency.spec.mjs', 'ce-inline-history.spec.mjs', 'ce-layouts.spec.mjs', 'ce-viewport.spec.mjs', 'ce-item-options.spec.mjs', 'refine-drafts.spec.mjs'] : 'development.spec.mjs',
    use: { baseURL: `http://127.0.0.1:${port + index}` },
  })),
  webServer: modes.map((mode, index) => ({
    command: `node start-server.mjs ${mode} ${port + index}`,
    url: `http://127.0.0.1:${port + index}`,
    reuseExistingServer: false,
    timeout: 120_000,
  })),
});
