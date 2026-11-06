// Gate 3 artwork disk cache (ADR 0019) on the real Emby test account, driven over
// CDP. Two launches on the same empty cache folder (`VIEWER_E2E_ARTWORK_DIR`, debug
// builds only, never the user's cache):
// - first launch: the posters of the films grid are downloaded and kept;
// - second launch: the same posters come from the disk, the server is not asked again.
// Read only on the server (image GETs).
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-artwork");
const cacheDir = mkdtempSync(join(tmpdir(), "viewer-e2e-artwork-"));
const POSTERS = 12;

const stats = (page) =>
  page.evaluate(() => window.__TAURI_INTERNALS__.invoke("artwork_cache_stats"));

/** Opens the films grid and waits for the first posters; returns their URLs. */
async function filmPosters(page) {
  await libraryNav(page).waitFor({ timeout: 20000 });
  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page)
    .nth(POSTERS - 1)
    .waitFor({ timeout: 20000 });
  const started = Date.now();
  const handle = await page.waitForFunction(
    (count) => {
      const imgs = [...document.querySelectorAll("button.card img")].slice(
        0,
        count,
      );
      return imgs.length === count &&
        imgs.every((i) => i.complete && i.naturalWidth > 0)
        ? imgs.map((i) => i.currentSrc)
        : false;
    },
    POSTERS,
    { timeout: 30000 },
  );
  return { urls: await handle.jsonValue(), ms: Date.now() - started };
}

const filesOnDisk = () =>
  readdirSync(cacheDir, { recursive: true }).filter((f) =>
    String(f).endsWith(".img"),
  );

let app = null;
try {
  // 1. Empty cache: the posters are downloaded, then kept on disk.
  app = await launch({ env: { VIEWER_E2E_ARTWORK_DIR: cacheDir } });
  let page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  const cold = await filmPosters(page);
  const first = await stats(page);
  check(
    "cold_posters_downloaded",
    first.misses >= POSTERS && first.entries >= POSTERS,
    `${cold.urls.length} affiches en ${cold.ms} ms ; ${JSON.stringify(first)}`,
  );
  check(
    "cold_cache_on_disk",
    // Other images (home rows) may still be written: at least the posters.
    filesOnDisk().length >= POSTERS,
    `${filesOnDisk().length} fichiers`,
  );
  check(
    "no_server_address_in_paths",
    filesOnDisk().every((f) => !String(f).includes("192.168")),
  );
  await shot(page, "films-cold");
  check("first_exit_code", (await app.close()) === 0);
  app = null;

  // 2. Same folder, new process: the same posters come from the disk.
  app = await launch({ env: { VIEWER_E2E_ARTWORK_DIR: cacheDir } });
  page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  const warm = await filmPosters(page);
  const second = await stats(page);
  check(
    "same_posters",
    JSON.stringify(warm.urls) === JSON.stringify(cold.urls),
  );
  check(
    "warm_posters_from_disk",
    second.hits >= POSTERS,
    `${warm.ms} ms (froid : ${cold.ms} ms) ; ${JSON.stringify(second)}`,
  );
  await shot(page, "films-warm");

  await signOut(page);
  check("exit_code", (await app.close()) === 0);
  app = null;
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
  finish();
}
