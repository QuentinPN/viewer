// UI evidence of the « Demander » screens (ADR 0125) on the real Emby test
// account and the real Request server, driven over CDP (no desktop takeover).
// Screenshots go to docs/evidence/request/:
// configuration, Découvrir, Parcourir, film page, series page with the request
// dialog open (never sent), Mes demandes, library search with « À demander »,
// Réglages › Demandes, « Lire » opening the Emby title of an available one
// (Mes demandes, else the Films grid), and « trop ancien » against a local fake server whose
// /api/version only lists `api.version`.
// Without --real-request, never sends a request nor cancels one: the totals of `ur_my_requests` are
// compared before and after. The debug app's request.json is moved aside at
// the start (setup screen) and put back at the end; the Request session made
// for the run is signed out (revoked) at the end, then Emby.
// The Request address is VIEWER_UR_URL (environment, else debug.env). Before
// each screenshot, every input or text showing it or the Emby address is
// masked on screen only (the stored settings are untouched).
import { existsSync, mkdirSync, renameSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { join } from "node:path";
import { launch, root } from "./app.mjs";
import {
  createReport,
  debugEnv,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const UR_URL = debugEnv("VIEWER_UR_URL");
const REAL = process.argv.includes("--real-request");
const TMDB_MOVIE = 1153576;
const account = testAccount();
/** Host names never shown in the evidence. */
const hidden = [UR_URL, account.url]
  .filter(Boolean)
  .map((u) => new URL(u).hostname);
const { check, finish } = createReport("requests-ui");
const evidence = join(root, "docs", "evidence", "request");
mkdirSync(evidence, { recursive: true });
const settingsFile = join(
  process.env.APPDATA ?? "",
  "io.github.quentinpn.viewer.debug",
  "request.json",
);
const backup = `${settingsFile}.e2e-backup`;

const invoke = (page, cmd, args = {}) =>
  page.evaluate(
    ([c, a]) => window.__TAURI_INTERNALS__.invoke(c, a),
    [cmd, args],
  );
/** Screenshot with the server addresses masked, then put back. */
const shot = async (page, name) => {
  await page.evaluate((hosts) => {
    const has = (t) => hosts.some((h) => t?.includes(h));
    window.__masked = [];
    for (const input of document.querySelectorAll("input")) {
      if (!has(input.value)) continue;
      window.__masked.push(() =>
        input.style.removeProperty("-webkit-text-security"),
      );
      input.style.setProperty("-webkit-text-security", "disc");
    }
    const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) {
      if (!has(n.nodeValue)) continue;
      const node = n;
      const text = node.nodeValue;
      window.__masked.push(() => {
        node.nodeValue = text;
      });
      node.nodeValue = hosts.reduce(
        (t, h) => t.replaceAll(h, "serveur.exemple.fr"),
        text,
      );
    }
  }, hidden);
  try {
    await page.screenshot({ path: join(evidence, `${name}.png`) });
  } finally {
    await page.evaluate(() => {
      for (const undo of window.__masked ?? []) undo();
      window.__masked = [];
    });
  }
};
const nav = (page, name) =>
  libraryNav(page).getByRole("button", { name, exact: true });
const segment = (page, name) =>
  page
    .getByRole("group", { name: "Sections de Demander" })
    .getByRole("button", { name });
/** The title page has its details (or its error). */
const loaded = (page) =>
  page.waitForFunction(
    () =>
      document
        .querySelector('section[aria-labelledby="ur-media-title"]')
        ?.getAttribute("aria-busy") === "false",
    null,
    { timeout: 20000 },
  );
const gridCards = (page) => page.locator("ul.ur-grid button.card");
const totals = async (page) =>
  JSON.stringify(
    (await invoke(page, "ur_my_requests", { status: null, page: 1 })).totals,
  );

/** /api/version of a server too old for Viewer. */
const fake = createServer((req, res) => {
  if (req.url?.startsWith("/api/version")) {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ apiVersion: "1.0.0", features: ["api.version"] }));
  } else {
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "introuvable" }));
  }
});

/** The read-only tour of the Demander screens (01–10). */
async function tour(page) {
  before = await totals(page);
  // Découvrir.
  await page
    .locator(".row button.card, li button.card")
    .first()
    .waitFor({ timeout: 30000 });
  await page.waitForTimeout(1500);
  check("Découvrir rows", (await page.locator("button.card").count()) > 0);
  await shot(page, "02-decouvrir");

  // Parcourir (Films) and a film page.
  await segment(page, "Films").click();
  await gridCards(page).first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(1500);
  await shot(page, "03-parcourir-films");
  await gridCards(page).first().click();
  await page.locator("#ur-media-title").waitFor({ timeout: 20000 });
  await loaded(page);
  await page.waitForTimeout(1500);
  check("film page", true, await page.locator("#ur-media-title").textContent());
  await shot(page, "04-fiche-film");
  await page.keyboard.press("Escape");
  await page
    .locator("#ur-media-title")
    .waitFor({ state: "detached", timeout: 10000 });

  // A series with something to request: dialog opened, never sent.
  await segment(page, "Séries").click();
  await gridCards(page).first().waitFor({ timeout: 30000 });
  let dialogShown = false;
  const count = Math.min(await gridCards(page).count(), 20);
  for (let i = 0; i < count && !dialogShown; i++) {
    await gridCards(page).nth(i).click();
    await page.locator("#ur-media-title").waitFor({ timeout: 20000 });
    await loaded(page);
    const ask = page.getByRole("button", { name: "Demander", exact: true });
    if (await ask.count()) {
      await ask.click();
      const dialog = page.locator("dialog[aria-labelledby=ur-dialog-title]");
      await dialog.waitFor({ timeout: 10000 });
      await page.waitForTimeout(1000);
      await shot(page, "05-fiche-serie-demande");
      dialogShown = true;
      await dialog
        .getByRole("button", { name: "Annuler", exact: true })
        .click();
      await dialog.waitFor({ state: "hidden", timeout: 10000 });
      await page.waitForTimeout(500);
      await page.keyboard.press("Escape");
      const back = await page
        .locator("#ur-media-title")
        .waitFor({ state: "detached", timeout: 3000 })
        .then(
          () => true,
          () => false,
        );
      check("Échap leaves the page after the dialog closed", back);
      if (back) break;
      await page.getByRole("button", { name: "Retour", exact: true }).click();
    } else {
      await page.keyboard.press("Escape");
    }
    await page
      .locator("#ur-media-title")
      .waitFor({ state: "detached", timeout: 10000 });
  }
  check("series request dialog shown (not sent)", dialogShown);

  // Mes demandes with the first request in the panel.
  await segment(page, /^Mes demandes/).click();
  await page
    .getByRole("group", { name: "Statut des demandes" })
    .waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => !document.querySelector(".list[aria-busy=true]"),
    null,
    { timeout: 20000 },
  );
  const rows = page.locator(".list button.row");
  if (await rows.count()) {
    await rows.first().click();
    await page
      .getByRole("list", { name: "Historique de la demande" })
      .waitFor({ timeout: 20000 })
      .catch(() => {});
  }
  await page.waitForTimeout(1000);
  check("Mes demandes", true, `${await rows.count()} rows`);
  await shot(page, "06-mes-demandes");

  // Library search with « À demander · Request ».
  await page.locator("#search-field").fill("Dune");
  await page
    .getByRole("heading", { name: "À demander · Request" })
    .waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => !document.querySelector(".ur-search[aria-busy=true]"),
    null,
    { timeout: 20000 },
  );
  await page.waitForTimeout(1500);
  await page.locator(".ur-search").scrollIntoViewIfNeeded();
  await page.waitForTimeout(800);
  check(
    "library search shows « À demander »",
    true,
    `${await page.locator(".ur-search button.card").count()} cards`,
  );
  await shot(page, "07-recherche-a-demander");
  await page.locator("#search-field").fill("");
  await page.keyboard.press("Escape");

  // Réglages › Demandes.
  await libraryNav(page).getByRole("button", { name: "Réglages" }).click();
  const section = page.locator("section[aria-labelledby=settings-requests]");
  await section.waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () =>
      document
        .querySelector("section[aria-labelledby=settings-requests]")
        ?.getAttribute("aria-busy") !== "true",
    null,
    { timeout: 20000 },
  );
  await section.scrollIntoViewIfNeeded();
  await page.waitForTimeout(800);
  await shot(page, "08-reglages-demandes");

  // « Lire »: a title Request knows on Emby opens the Emby detail page.
  // First a request of the account whose title is on Emby, else a film of the grid.
  await nav(page, "Demander").click();
  await segment(page, /^Mes demandes/).click();
  await page
    .getByRole("group", { name: "Statut des demandes" })
    .getByRole("button", { name: /^Toutes/ })
    .click();
  await page.waitForFunction(
    () => !document.querySelector(".list[aria-busy=true]"),
    null,
    { timeout: 20000 },
  );
  const mine = await invoke(page, "ur_my_requests", { status: null, page: 1 });
  const onEmby = mine.results.findIndex((r) => r.media?.embyItemId);
  let lire = null;
  if (onEmby >= 0) {
    await page.locator(".list button.row").nth(onEmby).click();
    lire = page.getByRole("button", { name: "Lire", exact: true });
    await lire.waitFor({ timeout: 20000 });
    check("« Lire » from Mes demandes", true, mine.results[onEmby].status);
  } else {
    await segment(page, "Films").click();
    await gridCards(page).first().waitFor({ timeout: 30000 });
    const films = Math.min(await gridCards(page).count(), 40);
    for (let i = 0; i < films && !lire; i++) {
      await gridCards(page).nth(i).click();
      await page.locator("#ur-media-title").waitFor({ timeout: 20000 });
      await loaded(page);
      const button = page.getByRole("button", { name: "Lire", exact: true });
      if (await button.count()) lire = button;
      else {
        await page.keyboard.press("Escape");
        await page
          .locator("#ur-media-title")
          .waitFor({ state: "detached", timeout: 10000 });
      }
    }
    check("« Lire » from the Films grid", lire !== null);
  }
  if (lire) {
    await lire.click();
    await page
      .locator(".detail button.play")
      .first()
      .waitFor({ timeout: 20000 })
      .catch(() => {});
    await page.waitForTimeout(1500);
    check(
      "« Lire » opens the Emby detail page",
      (await page.locator(".detail button.play").count()) > 0,
      await page.locator(".detail h1").first().textContent(),
    );
    await shot(page, "10-lire-ouvre-emby");
    // Back out of the Emby page and the title page to the tabs.
    for (let i = 0; i < 4 && !(await libraryNav(page).isVisible()); i++) {
      await page.keyboard.press("Escape");
      await page.waitForTimeout(800);
    }
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
  // Leave Demander, so the next visit reads the status again.
  await libraryNav(page).getByRole("button", { name: "Réglages" }).click();

  if ((await totals(page)) !== before)
    throw new Error("the request totals changed during the run");
  check("no request sent or cancelled", true, before);

  // « Trop ancien » against the local fake server.
  await new Promise((r) => fake.listen(0, "127.0.0.1", r));
  const port = fake.address().port;
  await invoke(page, "ur_set_address", { address: `http://127.0.0.1:${port}` });
  await nav(page, "Demander").click();
  await page
    .getByRole("heading", { name: "Serveur Request trop ancien" })
    .waitFor({ timeout: 20000 });
  check("« trop ancien » with a server lacking the features", true);
  await shot(page, "09-trop-ancien");
  await invoke(page, "ur_set_address", { address: UR_URL });
}

/** Opt-in (--real-request): one real request of the film TMDB_MOVIE through the
 * UI, then its cancellation (11–15). Stops before sending when the account
 * auto-approves films or the film is already requested or available. If the
 * request was made, it is cancelled even when a step fails. */
async function realRequest(page) {
  const status = await invoke(page, "ur_status");
  const caps = status.viewer.capabilities;
  const media = await invoke(page, "ur_media", {
    mediaType: "movie",
    tmdbId: TMDB_MOVIE,
  });
  check(
    "account and film read",
    true,
    JSON.stringify({
      role: status.viewer.role,
      autoApproveMovies: caps.autoApproveMovies,
      canRequestMovies: caps.canRequestMovies,
      quotaExempt: caps.quotaExempt,
      status: media.status,
    }),
  );
  if (caps.autoApproveMovies || !caps.canRequestMovies)
    throw new Error("stopped: films auto-approved or not requestable");
  if (media.status) throw new Error(`stopped: film status ${media.status}`);
  const quotaBefore = JSON.stringify(await invoke(page, "ur_quota"));
  const pendingBefore = await invoke(page, "ur_my_requests", {
    status: null,
    page: 1,
  });
  const known = new Set(pendingBefore.results.map((r) => r.id));
  let created = null;
  try {
    await page.getByLabel("Rechercher dans Request").fill(media.title);
    const year = String(media.year ?? "");
    const hit = page
      .locator("section[aria-labelledby=ur-search-title] li.row")
      .filter({
        has: page.locator(".title", {
          hasText: new RegExp(`^${media.title}$`),
        }),
      })
      .filter({ hasText: year })
      .first();
    await hit.waitFor({ timeout: 20000 });
    await hit.getByRole("button").click();
    await page.locator("#ur-media-title").waitFor({ timeout: 20000 });
    await loaded(page);
    const shownTitle = await page.locator("#ur-media-title").textContent();
    if (shownTitle?.trim() !== media.title)
      throw new Error(`wrong page: ${shownTitle}`);
    await page.getByRole("button", { name: "Demander", exact: true }).click();
    const dialog = page.locator("dialog[aria-labelledby=ur-dialog-title]");
    await dialog.waitFor({ timeout: 10000 });
    await page.waitForTimeout(1000);
    await shot(page, "11-demande-street-fighter");
    await dialog.getByRole("button", { name: "Demander", exact: true }).click();
    await page
      .getByRole("status")
      .filter({ hasText: "Demande envoyée" })
      .waitFor({ timeout: 20000 });
    const after = await invoke(page, "ur_my_requests", {
      status: null,
      page: 1,
    });
    created = after.results.find(
      (r) => !known.has(r.id) && r.media?.tmdbId === TMDB_MOVIE,
    );
    if (!created) throw new Error("request not found after sending");
    check("request sent", true, `#${created.id} ${created.status}`);
    await page.waitForTimeout(1000);
    await shot(page, "12-demande-envoyee");

    await page.keyboard.press("Escape");
    await page
      .locator("#ur-media-title")
      .waitFor({ state: "detached", timeout: 10000 });
    await page.getByLabel("Rechercher dans Request").fill("");
    await segment(page, /^Mes demandes/).click();
    await page.waitForFunction(
      () => !document.querySelector(".list[aria-busy=true]"),
      null,
      { timeout: 20000 },
    );
    const row = page
      .locator(".list button.row")
      .filter({ hasText: media.title })
      .first();
    await row.waitFor({ timeout: 20000 });
    await page.waitForTimeout(800);
    await shot(page, "13-mes-demandes");
    await row.click();
    await page
      .getByRole("list", { name: "Historique de la demande" })
      .waitFor({ timeout: 20000 });
    await page.waitForTimeout(800);
    await shot(page, "14-historique");
    await page.getByRole("button", { name: "Annuler la demande" }).click();
    await page.getByRole("button", { name: "Oui, annuler" }).click();
    await page
      .getByRole("button", { name: "Annuler la demande" })
      .waitFor({ state: "detached", timeout: 20000 });
    await page.waitForTimeout(1500);
    await shot(page, "15-annulee");
  } finally {
    if (created) {
      let d = await invoke(page, "ur_request_detail", { id: created.id });
      if (d.status !== "cancelled") {
        check("cancelled through the UI", false, d.status);
        d = await invoke(page, "ur_cancel", { id: created.id });
      }
      check("request cancelled", d.status === "cancelled", `#${created.id}`);
      const quotaAfter = JSON.stringify(await invoke(page, "ur_quota"));
      check("quota back", quotaAfter === quotaBefore, quotaAfter);
    }
  }
}

let app = null;
let before = null;
let urSignedIn = false;
try {
  if (existsSync(settingsFile)) renameSync(settingsFile, backup);
  app = await launch({ env: { VIEWER_EMBY_URL: account.url ?? "" } });
  const { page } = app;
  await page.setViewportSize({ width: 1440, height: 900 }).catch(() => {});

  if ((await startScreen(page)) === "signedOut")
    await signIn(page, account, account.password);
  await libraryNav(page).waitFor({ timeout: 30000 });

  // Configuration on the spot.
  await nav(page, "Demander").click();
  const setupTitle = page.getByRole("heading", {
    name: "Demander des films et des séries",
  });
  await setupTitle.waitFor({ timeout: 20000 });
  check("setup shown without an address", true);
  await shot(page, "01-configuration");

  await page.locator("#setup-ur-address").fill(UR_URL);
  await page.getByRole("button", { name: "Se connecter", exact: true }).click();
  await segment(page, "Découvrir").waitFor({ timeout: 30000 });
  urSignedIn = true;
  check("Request ready after the address", true);

  if (REAL) await realRequest(page);
  else await tour(page);
} catch (e) {
  check("scenario ran to the end", false, e?.stack ?? e);
} finally {
  if (app) {
    if (urSignedIn) {
      await invoke(app.page, "ur_sign_out").catch((e) =>
        check("Request sign-out", false, JSON.stringify(e)),
      );
    }
    await signOut(app.page).catch(() => {});
    await app.close().catch(() => {});
  }
  fake.close();
  rmSync(settingsFile, { force: true });
  if (existsSync(backup)) renameSync(backup, settingsFile);
  finish();
}
