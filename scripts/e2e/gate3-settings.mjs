// Gate 3 settings screen on the real Emby test account, driven over
// CDP, with an empty temporary artwork cache (`VIEWER_E2E_ARTWORK_DIR`, debug builds
// only, never the user's cache):
// - the home fills the cache; « Réglages » shows its size and image count, and
//   « À propos » the version of package.json;
// - « Vider le cache des images » removes every image file and announces it;
// - the home and search still work afterwards, and a library closes the settings;
// - « Diagnostics » offers « Ouvrir le dossier des journaux » (not clicked: it opens
//   the file explorer on the desktop); the log, in a temporary folder
//   (`VIEWER_E2E_LOG_DIR`), records the start and a panic raised on purpose
//   (`VIEWER_E2E_PANIC`) with its backtrace, and holds no secret.
// Read only on the server (image GETs); only the temporary folders are emptied.
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  homeHeading,
  libraryNav,
  resizeWindow,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-settings");
const cacheDir = mkdtempSync(join(tmpdir(), "viewer-e2e-settings-"));
const logDir = mkdtempSync(join(tmpdir(), "viewer-e2e-logs-"));
const FOREIGN = "notes.txt";

const stats = (page) =>
  page.evaluate(() => window.__TAURI_INTERNALS__.invoke("artwork_cache_stats"));
const images = () =>
  readdirSync(cacheDir, { recursive: true }).filter((f) =>
    String(f).endsWith(".img"),
  );
/** Text with every run of spaces as one plain space. */
const text = (s) => (s ?? "").replace(/\s+/g, " ").trim();
const settingsButton = (page) =>
  libraryNav(page).getByRole("button", { name: "Réglages", exact: true });
const panel = (page) =>
  page.locator("section[aria-labelledby=settings-artwork]");
const clearButton = (page) =>
  page.getByRole("button", { name: "Vider le cache des images" });

let app = null;
try {
  app = await launch({
    env: {
      VIEWER_E2E_ARTWORK_DIR: cacheDir,
      VIEWER_E2E_LOG_DIR: logDir,
      VIEWER_E2E_PANIC: "1",
    },
  });
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await homeHeading(page).waitFor({ timeout: 20000 });
  // Wait for the home rows' images to be written.
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll("img")].filter(
        (i) => i.complete && i.naturalWidth > 0,
      ).length >= 6,
    null,
    { timeout: 30000 },
  );
  await page.waitForTimeout(1000);
  writeFileSync(join(cacheDir, FOREIGN), "not ours");
  const before = await stats(page);
  check(
    "cache_filled_by_home",
    before.entries >= 6 && images().length === before.entries,
    JSON.stringify(before),
  );

  // Open the settings from the keyboard.
  await settingsButton(page).focus();
  await page.keyboard.press("Enter");
  await page.getByRole("heading", { name: "Réglages", level: 1 }).waitFor();
  check(
    "settings_current",
    (await settingsButton(page).getAttribute("aria-current")) === "page" &&
      (await libraryNav(page)
        .locator(".home-link")
        .getAttribute("aria-current")) === null,
  );
  await panel(page).locator(".size").waitFor({ timeout: 10000 });
  const shown = text(await panel(page).locator(".usage").textContent());
  check(
    "size_and_count_shown",
    / sur 512 Mo/.test(shown) && shown.includes(`${before.entries} images`),
    shown,
  );
  check("reads_since_launch", /Depuis le lancement : /.test(shown));
  // The version of the build, the one in package.json.
  const { version } = JSON.parse(
    readFileSync(new URL("../../package.json", import.meta.url), "utf8"),
  );
  const about = text(
    await page
      .getByRole("region", { name: "À propos" })
      .locator(".lead")
      .textContent(),
  );
  check("version_shown", about === `Viewer version ${version}`, about);
  // The diagnostics section and its button, reachable from the keyboard.
  const diagnostics = page.getByRole("region", { name: "Diagnostics" });
  const openLogs = diagnostics.getByRole("button", {
    name: "Ouvrir le dossier des journaux",
  });
  check(
    "diagnostics_shown",
    (await diagnostics.count()) === 1 && (await openLogs.isVisible()),
  );
  await openLogs.focus();
  check(
    "open_logs_focus_visible",
    await openLogs.evaluate((b) => getComputedStyle(b).outlineStyle !== "none"),
  );
  await shot(page, "settings-before");

  // Empty the cache.
  await clearButton(page).focus();
  check(
    "clear_button_focus_visible",
    await clearButton(page).evaluate(
      (b) => getComputedStyle(b).outlineStyle !== "none",
    ),
  );
  await page.keyboard.press("Enter");
  const status = page.locator(".panel [role=status]");
  await page.waitForFunction(
    () =>
      /Cache vidé|déjà vide/.test(
        document.querySelector(".panel [role=status]")?.textContent ?? "",
      ),
    null,
    { timeout: 15000 },
  );
  const announced = text(await status.textContent());
  check(
    "clear_announced",
    announced.startsWith(`Cache vidé : ${before.entries} images,`) &&
      announced.endsWith("libérés."),
    announced,
  );
  const after = await stats(page);
  check(
    "cache_empty",
    after.entries === 0 && after.bytes === 0 && images().length === 0,
    JSON.stringify(after),
  );
  check("foreign_file_kept", readdirSync(cacheDir).includes(FOREIGN));
  check(
    "screen_updated",
    text(await panel(page).locator(".size").textContent()).startsWith(
      "0 o sur 512 Mo",
    ),
  );
  check(
    "focus_kept_on_button",
    await clearButton(page).evaluate((b) => document.activeElement === b),
  );
  await shot(page, "settings-after");

  // Narrow window: the screen stays usable.
  resizeWindow(app.proc.pid, 720, 600);
  await page.waitForTimeout(400);
  check(
    "narrow_no_horizontal_scroll",
    await page.evaluate(() => {
      const c = document.querySelector("main.content");
      return c !== null && c.scrollWidth <= c.clientWidth + 1;
    }),
  );
  await shot(page, "settings-narrow");
  resizeWindow(app.proc.pid, 1280, 800);

  // A search replaces the settings, emptying it brings them back.
  const field = page.locator("#search-field");
  await field.fill("the");
  await page
    .getByRole("heading", { name: "Réglages", level: 1 })
    .waitFor({ state: "detached", timeout: 15000 });
  check(
    "search_takes_over",
    (await page
      .getByRole("heading", { name: "Réglages", level: 1 })
      .count()) === 0 &&
      (await settingsButton(page).getAttribute("aria-current")) === null,
  );
  await field.focus();
  await page.keyboard.press("Escape");
  await page.getByRole("heading", { name: "Réglages", level: 1 }).waitFor();
  check("back_to_settings_after_search", true);

  // The home comes back and downloads its images again.
  await libraryNav(page).locator(".home-link").click();
  await homeHeading(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () =>
      [...document.querySelectorAll("img")].filter(
        (i) => i.complete && i.naturalWidth > 0,
      ).length >= 6,
    null,
    { timeout: 30000 },
  );
  await page.waitForTimeout(1000);
  const refilled = await stats(page);
  check(
    "home_after_clear",
    (await settingsButton(page).getAttribute("aria-current")) === null,
    JSON.stringify(refilled),
  );

  // A library closes the settings too.
  await settingsButton(page).click();
  await page.getByRole("heading", { name: "Réglages", level: 1 }).waitFor();
  await libraryNav(page).locator("ul.libraries button").first().click();
  await cards(page).first().waitFor({ timeout: 20000 });
  check(
    "library_closes_settings",
    (await page
      .getByRole("heading", { name: "Réglages", level: 1 })
      .count()) === 0,
  );

  await signOut(page);
  check("exit_code", (await app.close()) === 0);
  app = null;

  // The log written during the run, after the app has closed.
  const log = readFileSync(join(logDir, "viewer.log"), "utf8");
  check(
    "log_started",
    log.includes(`Viewer ${version} started (debug build)`),
  );
  check(
    "log_panic_recorded",
    /ERROR panic: thread 'e2e-panic' panicked at src[\\/]lib\.rs:\d+:\d+: e2e panic, api_key=<redacted>/.test(
      log,
    ) && /\n\s+\d+: /.test(log),
  );
  const leaked = log.match(
    /(api_key|apikey|x-emby-token|accesstoken|"pw"|password)["']?\s*[:=]\s*["']?(?!<redacted>)[^\s&"',]+/gi,
  );
  check(
    "log_has_no_secret",
    !log.includes("e2e-secret-value") &&
      (account.password.length < 4 || !log.includes(account.password)) &&
      leaked === null,
    leaked ? `${leaked.length} unredacted value(s)` : "",
  );
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) await app.close().catch(() => {});
  rmSync(cacheDir, { recursive: true, force: true });
  rmSync(logDir, { recursive: true, force: true });
  finish();
}
