import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

// Isolated acceptance runs can target their own backend without changing normal development.
const apiTarget =
  process.env.VOLUNTEERFLOW_API_TARGET || "http://localhost:8080";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: apiTarget, changeOrigin: true },
      "/actuator": { target: apiTarget, changeOrigin: true },
    },
  },
  test: { environment: "jsdom", setupFiles: "./src/test/setup.ts" },
});
