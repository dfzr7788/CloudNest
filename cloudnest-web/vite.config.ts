import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  optimizeDeps: { include: ["@noble/hashes/sha2.js", "pdfjs-dist"] },
  preview: {
    allowedHosts: ["cloudnest.test"],
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/dav": "http://127.0.0.1:8080",
    },
  },
  server: {
    watch: { ignored: ["**/test-results*/**", "**/playwright-report/**"] },
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/actuator": "http://127.0.0.1:8080",
      "/dav": "http://127.0.0.1:8080",
    },
  },
});
