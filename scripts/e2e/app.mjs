// Drives the debug build through WebView2's DevTools protocol (CDP) instead of OS
// mouse and keyboard input: the app window never needs the foreground, so the
// desktop stays usable while a scenario runs. Screenshots come from the page, so
// they show the HTML UI only, never the native mpv video under it (ADR 0006).
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

export const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const exe = join(root, "src-tauri", "target", "debug", "viewer.exe");

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
 * The window opens off-screen without taking the foreground (`VIEWER_E2E_OFFSCREEN`,
 * debug builds only), and Chromium is told not to throttle it while hidden.
 */
export async function launch({
  port = 9339,
  env = {},
  timeoutMs = 30000,
} = {}) {
  // The test account is for the scenario only, never for the app process.
  const { VIEWER_EMBY_USER, VIEWER_EMBY_PASSWORD, ...inherited } = process.env;
  const proc = spawn(exe, [], {
    env: {
      ...inherited,
      ...env,
      VIEWER_E2E_OFFSCREEN: "1",
      WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: [
        `--remote-debugging-port=${port}`,
        "--disable-features=CalculateNativeWinOcclusion",
        "--disable-backgrounding-occluded-windows",
        "--disable-renderer-backgrounding",
      ].join(" "),
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
