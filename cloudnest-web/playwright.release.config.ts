import { defineConfig } from "@playwright/test";

// Run against the disposable Docker deployment, never a user's instance.
export default defineConfig({
  testDir: "./e2e",
  testMatch: [
    "cloudnest.spec.ts",
    "upload-recovery.spec.ts",
    "team-management.spec.ts",
    "resilience-workspace.spec.ts",
  ],
  workers: 1,
  timeout: 60000,
  use: {
    baseURL: process.env.CN_E2E_BASE_URL || "http://127.0.0.1:18088",
    channel: process.env.CI ? undefined : "chrome",
    headless: true,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  outputDir: "../.tools/stable-browser-results",
});
