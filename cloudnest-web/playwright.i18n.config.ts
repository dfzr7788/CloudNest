import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  testMatch: [
    "i18n.spec.ts",
    "agent-tools.spec.ts",
    "connected-runtime.spec.ts",
    "settings-layout.spec.ts",
  ],
  workers: 1,
  timeout: 45000,
  use: {
    baseURL: "http://127.0.0.1:15173",
    channel: process.env.CI ? undefined : "chrome",
    headless: true,
    screenshot: "only-on-failure",
  },
  outputDir: "../.tools/connected-i18n-results",
  webServer: {
    command: "npm run dev -- --port 15173 --strictPort",
    url: "http://127.0.0.1:15173",
    reuseExistingServer: false,
    timeout: 30000,
  },
});
