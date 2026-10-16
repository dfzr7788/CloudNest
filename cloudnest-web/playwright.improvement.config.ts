import { defineConfig } from "@playwright/test";
import base from "./playwright.config";
export default defineConfig({
  ...base,
  reporter: [
    ["list"],
    [
      "json",
      { outputFile: "../docs/evidence/improvement-browser-results.json" },
    ],
  ],
  outputDir: "test-results-improvement",
});
