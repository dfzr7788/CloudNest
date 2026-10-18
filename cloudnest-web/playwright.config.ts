import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  timeout: 60000,
  workers: 1,
  fullyParallel: false,
  reporter: [
    ["list"],
    ["json", { outputFile: "../.tools/ui-ux-refactor/browser-results.json" }],
  ],
  use: {
    baseURL: process.env.CN_E2E_BASE_URL || "http://127.0.0.1:5173",
    channel: "chrome",
    headless: true,
    launchOptions: process.env.CN_HTTP_PREVIEW_ORIGIN
      ? {
          args: [
            "--host-resolver-rules=MAP cloudnest.test 127.0.0.1",
            "--no-proxy-server",
          ],
        }
      : undefined,
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  outputDir: "../.tools/ui-ux-refactor/test-results-final",
});
