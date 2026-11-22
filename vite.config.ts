import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

// The build goes where go-app/cmd/viewer embeds it (`go:embed all:frontend`).
export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    watch: { ignored: ["**/go-app/**", "**/vendor/**", "**/artifacts/**"] },
  },
  build: {
    outDir: "go-app/cmd/viewer/frontend/dist",
    target: "es2022",
    sourcemap: false,
  },
  test: {
    include: ["src/**/*.test.ts"],
    environment: "node",
  },
});
