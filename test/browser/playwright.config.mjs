import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.TOVER_BROWSER_PORT || 33301);
const modes = ['production', 'development'];

export default defineConfig({
  testDir: '.',
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
    testMatch: `${mode}.spec.mjs`,
    use: { baseURL: `http://127.0.0.1:${port + index}` },
  })),
  webServer: modes.map((mode, index) => ({
    command: `node start-server.mjs ${mode} ${port + index}`,
    url: `http://127.0.0.1:${port + index}`,
    reuseExistingServer: false,
    timeout: 120_000,
  })),
});
