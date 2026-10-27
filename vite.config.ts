import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

// Tauri expects a fixed dev port and must not have Vite clear its logs.
// Desktop-only project: no TAURI_DEV_HOST / mobile HMR handling.
export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    watch: { ignored: ["**/src-tauri/**", "**/vendor/**", "**/artifacts/**"] },
  },
  build: {
    target: "es2022",
    sourcemap: false,
  },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
