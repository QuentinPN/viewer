// Shared pieces of the CDP scenarios: asserted checks, numbered screenshots,
// report.json, and the sign-in screen of the app.
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

/** Waits for the first screen: `"signedIn"` or `"signedOut"`. */
export async function startScreen(page) {
  await Promise.race([
    signInHeading(page).waitFor({ timeout: 20000 }),
    libraryNav(page).waitFor({ timeout: 20000 }),
  ]);
  return (await libraryNav(page).count()) > 0 ? "signedIn" : "signedOut";
}

export async function signOut(page) {
  await page.getByRole("button", { name: "Se déconnecter" }).click();
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

/** Fills the sign-in form; `url` overrides the prefilled server address. */
export async function signIn(page, { url, user }, password) {
  if (await page.locator("#server-url").count()) {
    if (url) await page.locator("#server-url").fill(url);
    await page.getByRole("button", { name: "Continuer" }).click();
    await page.locator("#username").waitFor({ timeout: 15000 });
  }
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Se connecter" }).click();
}
