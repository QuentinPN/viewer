// Drives the Go + Wails debug build through WebView2's DevTools protocol (CDP)
// instead of OS mouse and keyboard input: the app window never needs the
// foreground, so the desktop stays usable while a scenario runs. Screenshots come
// from the page, so they show the HTML UI only, never the native mpv video under
// it (print-window.ps1 captures the app's own window when a scenario needs it).
//
//   node scripts/e2e/app.mjs --build   builds the frontend and the debug exe
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

export const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
/** The debug build: frontend embedded, VIEWER_E2E_* variables honoured. */
export const debugExe = join(root, "go-app", "bin", "viewer-debug.exe");

/** The Go method every command goes through (src/lib/native/wails.ts). */
const INVOKE_METHOD = "viewer/internal/bridge.Bridge.Invoke";

/**
 * Installed in the page: `window.__viewer.invoke(cmd, args)` calls a command
 * through the Wails bridge and rejects like src/lib/native/wails.ts (the Go
 * error value itself); `window.__viewer.window(method)` calls a method of the
 * Wails runtime's Window (ToggleMaximise, IsMaximised…).
 */
function pageHelpers(method) {
  const runtime = () => import("/wails/runtime.js");
  window.__viewer = {
    invoke: async (cmd, args = {}) => {
      const rt = await runtime();
      try {
        return await rt.Call.ByName(method, cmd, args);
      } catch (e) {
        if (e instanceof Error) {
          throw e.name === "RuntimeError" && e.cause !== undefined
            ? e.cause
            : e.message;
        }
        throw String(e);
      }
    },
    window: async (name) => (await runtime()).Window[name](),
  };
}

/** `npm run build`, then a debug `go build` of go-app/cmd/viewer. */
export function buildDebug() {
  const opts = { cwd: root, stdio: "inherit", shell: true, windowsHide: true };
  if (spawnSync("npm", ["run", "build"], opts).status !== 0)
    throw new Error("npm run build a échoué");
  const go = spawnSync(
    "go",
    ["build", "-ldflags", "-H=windowsgui", "-o", debugExe, "./cmd/viewer"],
    {
      cwd: join(root, "go-app"),
      stdio: "inherit",
      windowsHide: true,
      env: { ...process.env, CGO_ENABLED: "0" },
    },
  );
  if (go.status !== 0) throw new Error("go build a échoué");
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForCdp(port, proc, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (proc.exitCode !== null)
      throw new Error(`Viewer exited early (code ${proc.exitCode})`);
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (res.ok) return;
    } catch {
      // Not listening yet.
    }
    await sleep(250);
  }
  throw new Error(`CDP port ${port} not open after ${timeoutMs} ms`);
}

/** A previous WebView2 may outlive its app by a moment, still holding the port. */
async function waitForCdpClosed(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      await fetch(`http://127.0.0.1:${port}/json/version`);
    } catch {
      return;
    }
    await sleep(250);
  }
  throw new Error(`CDP port ${port} still open after ${timeoutMs} ms`);
}

/** Output folder `artifacts/<group>/<name>-<timestamp>`. */
export function runDir(group, name) {
  const stamp = new Date()
    .toISOString()
    .replace(/[-:]/g, "")
    .replace("T", "-")
    .slice(0, 15);
  const dir = join(root, "artifacts", group, `${name}-${stamp}`);
  mkdirSync(dir, { recursive: true });
  return dir;
}

/**
 * Starts the debug build with a DevTools port on 127.0.0.1 and attaches to its page.
 * The window opens off-screen (`VIEWER_E2E_OFFSCREEN`, debug builds only), and
 * Chromium is told not to throttle it while hidden. Wails blanks
 * WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: the debug build reads the DevTools
 * arguments from VIEWER_E2E_BROWSER_ARGS instead. A release build ignores every
 * `VIEWER_*` variable, so it opens no DevTools port and cannot be attached.
 */
export async function launch({
  port = 9339,
  env = {},
  timeoutMs = 30000,
  exe = debugExe,
} = {}) {
  // The test account is for the scenario only, never for the app process.
  const { VIEWER_EMBY_USER, VIEWER_EMBY_PASSWORD, ...inherited } = process.env;
  await waitForCdpClosed(port, timeoutMs);
  const browserArgs = [
    `--remote-debugging-port=${port}`,
    "--disable-features=CalculateNativeWinOcclusion",
    "--disable-backgrounding-occluded-windows",
    "--disable-renderer-backgrounding",
  ].join(" ");
  const proc = spawn(exe, [], {
    env: {
      ...inherited,
      ...env,
      VIEWER_E2E_OFFSCREEN: "1",
      // No sound on the user's speakers during a scenario (debug builds only).
      VIEWER_E2E_SILENT: "1",
      VIEWER_E2E_BROWSER_ARGS: browserArgs,
    },
    stdio: "ignore",
  });
  await waitForCdp(port, proc, timeoutMs);
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const context = browser.contexts()[0];
  const page = context.pages()[0] ?? (await context.waitForEvent("page"));
  // The page starts on about:blank before WebView2 navigates to the app.
  await page.waitForURL((u) => u.protocol.startsWith("http"), {
    timeout: timeoutMs,
  });
  await page.waitForLoadState("domcontentloaded");
  // In every document the page loads from now on, and in the current one.
  const helpers = `(${pageHelpers})(${JSON.stringify(INVOKE_METHOD)})`;
  await page.addInitScript(helpers);
  await page.evaluate(helpers);

  /** Closes the window like the ✕ button (WM_CLOSE) and waits for the exit code. */
  async function close(timeout = 10000) {
    await browser.close().catch(() => {});
    if (proc.exitCode !== null) return proc.exitCode;
    const exited = new Promise((r) => proc.once("exit", (code) => r(code)));
    spawnSync("taskkill", ["/PID", String(proc.pid)], { stdio: "ignore" });
    const code = await Promise.race([exited, sleep(timeout).then(() => null)]);
    if (code === null) {
      spawnSync("taskkill", ["/F", "/PID", String(proc.pid)], {
        stdio: "ignore",
      });
      throw new Error("Viewer did not exit after WM_CLOSE");
    }
    return code;
  }

  return { page, proc, close };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (process.argv.includes("--build")) buildDebug();
}
