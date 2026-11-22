import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vitest/config";

// Tauri expects a fixed dev port and must not have Vite clear its logs.
// Desktop-only project: no TAURI_DEV_HOST / mobile HMR handling.
export default defineConfig(({ mode }) => ({
  plugins: [svelte()],
  clearScreen: false,
  // The native backend of src/lib/native: mode "wails"
  // (`npm run build:wails`) selects Wails, any other mode Tauri. A literal,
  // so the bundler drops the unused backend.
  define: {
    "import.meta.env.VITE_NATIVE": JSON.stringify(
      mode === "wails" ? "wails" : "tauri",
    ),
  },
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
}));
