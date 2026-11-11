// Shared pieces of the CDP scenarios: asserted checks, numbered screenshots,
// report.json, and the sign-in screen of the app.
import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { runDir } from "./app.mjs";

/** Test account from run.ps1 (Windows Credential Manager). */
export function testAccount() {
  const {
    VIEWER_EMBY_URL: url,
    VIEWER_EMBY_USER: user,
    VIEWER_EMBY_PASSWORD: password,
  } = process.env;
  if (!user || !password)
    throw new Error("VIEWER_EMBY_USER and VIEWER_EMBY_PASSWORD are required");
  return { url, user, password };
}

/** Collects checks and screenshots in `artifacts/e2e/<name>-<run>`. */
export function createReport(name) {
  const out = runDir("e2e", name);
  const checks = [];
  const report = { out, startedAt: new Date().toISOString(), checks };
  let shots = 0;
  return {
    out,
    check(checkName, passed, detail = "") {
      checks.push({
        name: checkName,
        passed: Boolean(passed),
        detail: String(detail),
      });
      console.log(
        `${passed ? "PASS" : "FAIL"} ${checkName}${detail ? ` — ${detail}` : ""}`,
      );
    },
    async shot(page, shotName) {
      await page.screenshot({
        path: join(out, `${String(shots++).padStart(2, "0")}-${shotName}.png`),
      });
    },
    /** Writes report.json and sets the exit code. */
    finish() {
      report.finishedAt = new Date().toISOString();
      report.passed = checks.length > 0 && checks.every((c) => c.passed);
      writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2));
      console.log(`${report.passed ? "ALL PASSED" : "FAILURES"} — ${out}`);
      process.exitCode = report.passed ? 0 : 1;
    },
  };
}

export const signInHeading = (page) => page.locator("#signin-title");
export const libraryNav = (page) =>
  page.getByRole("navigation", { name: "Bibliothèques" });
export const cards = (page) => page.locator("ul.grid button.card");
/** Home page, the default view once signed in (ADR 0010). */
export const homeHeading = (page) => page.locator(".home h1");
export const detailTitle = (page) => page.locator("#detail-title");
/** Episode page nested over a series page (ADR 0016). */
export const episodeTitle = (page) => page.locator("#episode-title");

/** Waits for the first screen: `"signedIn"` or `"signedOut"`. */
export async function startScreen(page) {
  await Promise.race([
    signInHeading(page).waitFor({ timeout: 20000 }),
    libraryNav(page).waitFor({ timeout: 20000 }),
  ]);
  return (await libraryNav(page).count()) > 0 ? "signedIn" : "signedOut";
}

/** Account actions sit in the menu of the navigation pill (ADR 0031). */
export async function accountAction(page, name) {
  const account = page.getByRole("button", { name: /^Compte : / });
  // The navigation is hidden on detail pages: Escape closes them.
  for (let i = 0; i < 3 && !(await account.isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(400);
  }
  await account.click();
  await page.getByRole("button", { name, exact: true }).click();
}

export async function signOut(page) {
  await accountAction(page, "Se déconnecter");
  await signInHeading(page).waitFor({ timeout: 15000 });
}

/** Badge of the player showing how the server delivers the media. */
export const playerBadge = (page) =>
  page.locator('[title="Décision du serveur Emby"]');

/** Engine diagnostics (`timePos`, `duration`, codecs…), or `{ ok: false }`. */
export const diagnostics = (page) =>
  page.evaluate(() =>
    window.__TAURI_INTERNALS__
      .invoke("player_diagnostics")
      .then((d) => ({ ok: true, ...d }))
      .catch((e) => ({ ok: false, error: String(e) })),
  );

/** Polls diagnostics until `accept(d)`; returns the last snapshot either way. */
export async function waitForDiagnostics(page, accept, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await diagnostics(page);
    if (last.ok && accept(last)) return last;
    await page.waitForTimeout(250);
  }
  return last;
}

/** Waits until the engine reports a position past `seconds`. */
export const waitForPosition = (page, seconds, timeoutMs) =>
  waitForDiagnostics(page, (d) => (d.timePos ?? 0) > seconds, timeoutMs);

/**
 * From the pre-connection shelf (ADR 0103), opens « Connexion manuelle »; does
 * nothing when a sign-in card (manual or password) is already open.
 */
export async function openManualForm(page) {
  if (
    (await page.locator("#server-url").count()) ||
    (await page.locator("#password").count())
  )
    return;
  await page
    .getByRole("button", { name: "Se connecter manuellement", exact: true })
    .click();
  await page.locator("#server-url").waitFor({ timeout: 10000 });
}

/**
 * Signs in with the manual form (address, user, password on one card), or
 * with the password card when the server asks the password again (`url` is
 * then ignored). `url` overrides the prefilled server address.
 */
export async function signIn(page, { url, user }, password) {
  await openManualForm(page);
  if (url && (await page.locator("#server-url").count()))
    await page.locator("#server-url").fill(url);
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Se connecter", exact: true }).click();
}

/** Tile of a saved server on the shelf, by its server name (ADR 0103). */
export const shelfTile = (page, name) =>
  page.getByRole("button", {
    name: new RegExp(
      `^Se connecter à ${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")} \\(`,
    ),
  });

/** Moves and resizes the real window without activating it (SetWindowPos). */
export function resizeWindow(pid, width, height) {
  const ps = `
Add-Type -Name P -Namespace U -MemberDefinition '[DllImport("user32.dll")] public static extern bool SetWindowPos(System.IntPtr h, System.IntPtr a, int x, int y, int cx, int cy, uint f);'
$h = (Get-Process -Id ${pid}).MainWindowHandle
if ($h -eq 0) { exit 2 }
if (-not [U.P]::SetWindowPos($h, [System.IntPtr]::Zero, 0, 0, ${width}, ${height}, 0x0016)) { exit 3 }`;
  const r = spawnSync("powershell", ["-NoProfile", "-Command", ps], {
    encoding: "utf8",
  });
  if (r.status !== 0)
    throw new Error(`resize failed (${r.status}) ${r.stderr}`);
}

/**
 * Windows display scaling without the system setting: the real window gets the
 * CSS size, then CDP sets the device scale factor. `cdp` is a CDP session of the page.
 */
export async function setScaledSize(app, cdp, { width, height, scale }) {
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  resizeWindow(app.proc.pid, width, height);
  await app.page
    .waitForFunction((w) => Math.abs(innerWidth - w) <= 24, width, {
      timeout: 10000,
    })
    .catch(async () => {
      const got = await app.page.evaluate(() => `${innerWidth}×${innerHeight}`);
      throw new Error(`window is ${got}, wanted ${width}×${height}`);
    });
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 0,
    height: 0,
    deviceScaleFactor: scale,
    mobile: false,
  });
  await app.page.waitForFunction((s) => devicePixelRatio === s, scale, {
    timeout: 10000,
  });
  await app.page.waitForTimeout(500);
}
