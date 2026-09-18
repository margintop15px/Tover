import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: ["workspace-switch.spec.ts", "workspace-invites.spec.ts"],
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: "list",
  outputDir: "test-results/workspace",
  use: { ...devices["Desktop Chrome"], baseURL: "http://127.0.0.1:3410", trace: "retain-on-failure" },
  webServer: [
    {
      command: "node_modules/.bin/tsx tests/fixtures/workspace-supabase.ts",
      url: "http://127.0.0.1:3411/__test",
      reuseExistingServer: false,
    },
    {
      command: "node_modules/.bin/next dev --hostname 127.0.0.1 --port 3410",
      url: "http://127.0.0.1:3410/login",
      reuseExistingServer: false,
      timeout: 120_000,
      env: {
        NEXT_DIST_DIR: ".next-e2e",
        NEXT_PUBLIC_SUPABASE_URL: "http://127.0.0.1:3411",
        NEXT_PUBLIC_SUPABASE_ANON_KEY: "workspace-test-anon-key",
        SUPABASE_SERVICE_ROLE_KEY: "workspace-test-service-key",
        NEXT_PUBLIC_SITE_URL: "http://127.0.0.1:3410",
        SENTRY_AUTH_TOKEN: "", SENTRY_DSN: "", NEXT_PUBLIC_SENTRY_DSN: "", OPENAI_API_KEY: "",
      },
    },
  ],
});
