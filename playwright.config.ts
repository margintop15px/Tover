import { defineConfig, devices } from "@playwright/test";

const port = Number(process.env.PORT || "3000");
const baseURL = process.env.PLAYWRIGHT_BASE_URL || `http://127.0.0.1:${port}`;
const ozonMockPort = Number(process.env.OZON_MOCK_PORT || "32123");
const ozonApiBaseURL =
  process.env.OZON_API_BASE_URL || `http://127.0.0.1:${ozonMockPort}`;
const ozonCredentialEncryptionKey =
  process.env.OZON_CREDENTIAL_ENCRYPTION_KEY ||
  "playwright-ozon-credential-encryption-key";

process.env.OZON_API_BASE_URL = ozonApiBaseURL;
process.env.OZON_CREDENTIAL_ENCRYPTION_KEY = ozonCredentialEncryptionKey;

function shellValue(value: string): string {
  return JSON.stringify(value);
}

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL,
    trace: "on-first-retry",
    video: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "setup",
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: "chromium-public",
      testIgnore: [/auth\.setup\.ts/, /authenticated\.spec\.ts/],
      use: {
        ...devices["Desktop Chrome"],
      },
    },
    {
      name: "chromium-authenticated",
      dependencies: ["setup"],
      testMatch: /authenticated\.spec\.ts/,
      use: {
        ...devices["Desktop Chrome"],
        storageState: "playwright/.auth/user.json",
      },
    },
  ],
  webServer: process.env.PLAYWRIGHT_SKIP_WEBSERVER
    ? undefined
    : {
        command: [
          `OZON_API_BASE_URL=${shellValue(ozonApiBaseURL)}`,
          `OZON_CREDENTIAL_ENCRYPTION_KEY=${shellValue(ozonCredentialEncryptionKey)}`,
          `node node_modules/next/dist/bin/next dev --port ${port}`,
        ].join(" "),
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120 * 1000,
      },
});
