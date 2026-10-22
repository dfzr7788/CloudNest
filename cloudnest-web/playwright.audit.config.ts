import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  testMatch: [
    "bug-audit.spec.ts",
    "settings-layout.spec.ts",
    "team-management.spec.ts",
  ],
  workers: 1,
  timeout: 45000,
  use: {
    baseURL: "http://127.0.0.1:15175",
    channel: process.env.CI ? undefined : "chrome",
    screenshot: "only-on-failure",
  },
  outputDir: "../.tools/bug-audit-results",
  webServer: {
    command: "npm run dev -- --port 15175 --strictPort",
    url: "http://127.0.0.1:15175",
    reuseExistingServer: false,
  },
});
