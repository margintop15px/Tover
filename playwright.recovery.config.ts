import { defineConfig, devices } from "@playwright/test";

if (process.env.TOVER_LOCAL_AUTH !== "1" || process.env.NEXT_PUBLIC_SUPABASE_URL !== "http://127.0.0.1:3421") {
  throw new Error("Run via bash scripts/test-auth-recovery.sh (disposable local services only)");
}
export default defineConfig({
  testDir: "./tests",
  testMatch: ["**/auth-callback.spec.ts", "**/auth-recovery.spec.ts"],
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: "list",
  outputDir: "test-results/recovery",
  use: { ...devices["Desktop Chrome"], baseURL: "http://127.0.0.1:3420", trace: "retain-on-failure" },
  webServer: [
    { command: "node_modules/.bin/tsx tests/fixtures/local-supabase-proxy.ts", url: "http://127.0.0.1:3421/health", reuseExistingServer: false },
    { command: "node_modules/.bin/next dev --hostname 127.0.0.1 --port 3420", url: "http://127.0.0.1:3420/login", reuseExistingServer: false,
      timeout: 120_000, env: { NEXT_DIST_DIR: ".next-recovery" } },
  ],
});
