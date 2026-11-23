// « Regarder maintenant » on the Demander pages of the Go + Wails debug build,
// driven over CDP like stremio-sources (no OS input; the window stays
// off-screen, no sound with VIEWER_E2E_SILENT, captured with PrintWindow on its
// own HWND).
//
// Steps: Emby sign-in with the debug.env account → Stremio sign-in from
// Réglages → Demander, set up on the spot with the Request address of
// debug.env (VIEWER_UR_URL) → a film found by `ur_search`, outside the Emby
// library, with an IMDb id, opened from the Demander search: « Regarder
// maintenant » shown, the panel opens with the focus inside, at least one addon
// with streams, no address in the dialog → the first stream that plays (up to
// three other addons): `fileLoaded` within 30 s, the position moves 3 s, badge
// « Stremio », held past Emby's 10 s progress interval after a seek to 40 % →
// closed: back on the Demander page with the focus on « Regarder maintenant »,
// the Emby « Reprendre » list unchanged (the title has no Emby item to carry a
// resume point) → a series outside Emby with an IMDb id: the picker « Choisir
// un épisode » opens on its season list (Échap gives the focus back), season 1
// episode 1 opens the panel with at least one addon with streams, Échap
// closes it, focus back on the button → always at the end: Stremio sign-out
// (no `stremio` vault entry), Request sign-out (session revoked), Emby
// sign-out, no Viewer-debug vault entry. The app log is checked for
// manifest addresses, stream addresses and the authKey.
//
// Accounts as stremio-sources: the Emby account and the Request address
// from debug.env, the Stremio e-mail and password from the files named by
// ST_EMAIL_FILE / ST_PASS_FILE (required); none of them reaches the console,
// report.json or a capture. Only titles and addon names are printed.
//
// The app runs with APPDATA, LOCALAPPDATA and its log in a temporary folder,
// deleted at the end; the Windows vault is shared, so the run refuses to start
// over an existing Viewer-debug or stremio entry and always ends signed out.
//
//   node scripts/e2e/stremio-watch-now.mjs [--build] [--out <dir>]
//
// --build  npm run build, then a debug `go build` (app.mjs buildDebug)
// --out    folder of the captures and report.json
//          (default: <tmp>/viewer-stremio-watch-now-<timestamp>)
import { existsSync } from "node:fs";
import { basename } from "node:path";
import { buildDebug, debugExe as exe, launch } from "./app.mjs";
import {
  invoke,
  libraryNav,
  signInHeading,
  signOut,
  startScreen,
} from "./scenario.mjs";
import {
  addonsSummary,
  attemptsText,
  capture,
  checkLogAndVault,
  closePanel,
  decision,
  dialogLeaks,
  focusInfo,
  holdPastProgressInterval,
  lastStremioSignOut,
  leavePlayer,
  playFirstWorking,
  poll,
  readAccounts,
  readyApp,
  removeWork,
  runFolders,
  scrubbedReport,
  scrubber,
  signInEmbyAndStremio,
  signOutStremio,
  sourcesDialog,
  toNav,
  vaultTargets,
  waitSettled,
} from "./stremio.mjs";

const argv = process.argv.slice(2);
const PORT = 9346;
const WATCH = "Regarder maintenant";
/** Well-known titles tried in order: the first outside the Emby library wins. */
const FILMS = [
  "The Shawshank Redemption",
  "Inception",
  "The Godfather",
  "Pulp Fiction",
  "Fight Club",
  "Forrest Gump",
  "The Matrix",
  "Interstellar",
  "Se7en",
  "Gladiator",
];
const SERIES = [
  "Breaking Bad",
  "The Wire",
  "Chernobyl",
  "The Sopranos",
  "True Detective",
  "Band of Brothers",
];
/** First addon with streams, then up to this many others. */
const OTHER_ATTEMPTS = 3;

const { out, work, logFile, appEnv } = runFolders("stremio-watch-now", argv);

const { account, urUrl, stremio, secrets } = readAccounts();
const { scrub, firstLine } = scrubber(secrets);
const { report, check, finish } = scrubbedReport(scrub, {
  build: "debug (coffre Viewer-debug)",
});
report.facts = {};
const fact = (name, value) => {
  report.facts[name] = JSON.parse(scrub(JSON.stringify(value ?? null)));
};
/** The step running, named in a scenario error. */
let step = "démarrage";

// ---- Demander -------------------------------------------------------------------

const nav = (page, name) =>
  libraryNav(page).getByRole("button", { name, exact: true });
const segment = (page, name) =>
  page
    .getByRole("group", { name: "Sections de Demander" })
    .getByRole("button", { name });
const searchField = (page) => page.getByLabel("Rechercher dans Request");
const mediaPage = (page) =>
  page.locator('section[aria-labelledby="ur-media-title"]');
const mediaTitle = (page) => page.locator("#ur-media-title");
const watchButton = (page) =>
  mediaPage(page).getByRole("button", { name: WATCH, exact: true });
const picker = (page) => page.locator("dialog[aria-labelledby=episodes-title]");
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Demander from the navigation, set up with the Request address if asked. */
async function openRequests(page) {
  await nav(page, "Demander").click();
  const setup = page.locator("#setup-ur-address");
  const ready = segment(page, "Découvrir");
  await Promise.any([
    setup.waitFor({ timeout: 30000 }),
    ready.waitFor({ timeout: 30000 }),
  ]);
  if (await setup.isVisible()) {
    await setup.fill(urUrl);
    state.ur = true;
    await page
      .getByRole("button", { name: "Se connecter", exact: true })
      .click();
  }
  await ready.waitFor({ timeout: 30000 });
  state.ur = true;
}

/**
 * The first of `queries` whose first `type` result is outside Emby with an
 * IMDb id (and, for a film, Stremio addons to ask), from the API.
 */
async function pickTitle(page, type, queries) {
  const tried = [];
  for (const query of queries) {
    const found = await invoke(page, "ur_search", { query, page: 1 });
    const hit = found.results.find((m) => m.type === type);
    if (!hit) {
      tried.push(`${query} : absent`);
      continue;
    }
    const d = await invoke(page, "ur_media", {
      mediaType: type,
      tmdbId: hit.tmdbId,
    });
    if (hit.embyItemId || d.embyItemId) {
      tried.push(`${hit.title} : dans Emby`);
      continue;
    }
    const imdb = d.externalIds?.imdb ?? null;
    if (!imdb) {
      tried.push(`${hit.title} : sans IMDb`);
      continue;
    }
    if (type === "movie") {
      const s = await invoke(page, "stremio_sources", {
        external: { imdb, name: d.title },
      });
      if (!(s.addons ?? []).length) {
        tried.push(`${hit.title} : aucun addon`);
        continue;
      }
    }
    return { query, title: hit.title, year: hit.year ?? null, tried };
  }
  return { title: null, tried };
}

/** The Demander page of `pick`, opened from a search row; waits for its details. */
async function openFromSearch(page, pick) {
  await searchField(page).fill(pick.query);
  const exact = new RegExp(`^${escapeRegExp(pick.title)}$`);
  let row = page
    .locator("section[aria-labelledby=ur-search-title] li.row")
    .filter({ has: page.locator(".title", { hasText: exact }) });
  if (pick.year) row = row.filter({ hasText: String(pick.year) });
  row = row.first();
  await row.waitFor({ timeout: 20000 });
  await row.getByRole("button").click();
  await mediaTitle(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () =>
      document
        .querySelector('section[aria-labelledby="ur-media-title"]')
        ?.getAttribute("aria-busy") === "false",
    null,
    { timeout: 20000 },
  );
  const shown = (await mediaTitle(page).textContent())?.trim();
  if (shown !== pick.title) throw new Error(`mauvaise page : « ${shown} »`);
}

/** Back to the Demander search, the page closed. */
async function closeMedia(page) {
  if (await mediaTitle(page).isVisible()) {
    await page.keyboard.press("Escape");
    await mediaTitle(page).waitFor({ state: "detached", timeout: 10000 });
  }
  await searchField(page).fill("");
}

async function openWatchPanel(page) {
  await watchButton(page).click();
  await sourcesDialog(page).waitFor({ state: "visible", timeout: 10000 });
}

/** The Emby ids of « Reprendre », in order. */
const continueIds = async (page) =>
  (await invoke(page, "emby_continue_watching")).map((w) => w.id).join(",");

/** Tag of the focused element and whether it is in the episode picker. */
const pickerFocus = (page) =>
  page.evaluate(() => {
    const a = document.activeElement;
    return {
      tag: a?.tagName ?? "",
      inPicker: !!a?.closest("dialog[aria-labelledby=episodes-title]"),
    };
  });

// ---- steps ------------------------------------------------------------------------

async function filmSteps(app) {
  const { page } = app;
  step = "film : choix";
  const pick = await pickTitle(page, "movie", FILMS);
  fact("filmTried", pick.tried);
  if (!pick.title)
    throw new Error(
      `aucun film hors Emby avec IMDb (${pick.tried.join(", ")})`,
    );
  step = "film : page";
  await openFromSearch(page, pick);
  check("film_found_outside_emby", true, `« ${pick.title} » (${pick.year})`);
  const button = watchButton(page);
  await button.waitFor({ timeout: 10000 });
  check("watch_now_shown", await button.isVisible());

  step = "film : panneau";
  await openWatchPanel(page);
  const focus = await focusInfo(page);
  const modal = await sourcesDialog(page).evaluate(
    (d) => d.open && d.matches(":modal"),
  );
  check(
    "panel_modal_focus_inside",
    modal && focus.inDialog,
    `modal ${modal}, focus ${focus.tag} « ${focus.text} »`,
  );
  const settled = await waitSettled(page);
  fact(
    "filmAddons",
    settled.addons.map((a) => ({
      name: a.name,
      state: a.state,
      streams: a.streams,
    })),
  );
  check(
    "film_addons_with_streams",
    settled.addons.some((a) => a.state === "ok") &&
      !settled.searching &&
      !settled.addons.some((a) => a.state === "loading"),
    addonsSummary(settled),
  );
  const leaks = await dialogLeaks(page);
  check(
    "dialog_without_addresses",
    !leaks.address && !leaks.manifest && !leaks.debridHost,
    `adresse ${leaks.address}, manifest.json ${leaks.manifest}, hôte debrid ${leaks.debridHost}`,
  );
  const shotPanel = await capture(app, out, "regarder-maintenant", secrets);
  check(
    "capture_panel",
    shotPanel.printed && shotPanel.distinctColors > 20,
    `${basename(shotPanel.file)}, ${shotPanel.distinctColors} couleurs`,
  );

  step = "film : lecture";
  const before = await continueIds(page);
  const { attempts, played } = await playFirstWorking(
    page,
    () => openWatchPanel(page),
    OTHER_ATTEMPTS,
  );
  fact("playAttempts", attempts);
  check("stremio_playback", played !== null, attemptsText(attempts));
  if (!played) {
    if (await sourcesDialog(page).isVisible()) await closePanel(page);
    await closeMedia(page);
    return;
  }
  check(
    "file_loaded_within_30s",
    played.loadedMs <= 30000,
    `${played.loadedMs} ms`,
  );
  const badge = (await decision(page).textContent())?.trim();
  check("badge_stremio", badge === "Stremio", badge);
  const { duration, holdFrom, stopAt } = await holdPastProgressInterval(page);
  await page.mouse.move(640, 360);
  await page.mouse.move(650, 370);
  const shotPlay = await capture(
    app,
    out,
    "lecture-regarder-maintenant",
    secrets,
  );
  check(
    "capture_playing",
    shotPlay.printed && shotPlay.distinctColors > 50,
    `${basename(shotPlay.file)}, ${shotPlay.distinctColors} couleurs`,
  );
  fact("stremioStop", {
    duration,
    holdFrom: Math.round(holdFrom),
    stopAt: Math.round(stopAt),
  });

  step = "film : fermeture du lecteur";
  await leavePlayer(page);
  const onPage = await mediaTitle(page).isVisible();
  const after = await focusInfo(page);
  check(
    "player_closed_back_on_page_focus_on_button",
    onPage && after.text === WATCH,
    `page Demander ${onPage}, focus ${after.tag} « ${after.text} »`,
  );
  // The title has no Emby item: a report would show in « Reprendre ».
  const now = await poll(
    () => continueIds(page),
    (ids) => ids !== before,
    15000,
    1500,
  );
  check(
    "no_emby_report",
    now === before,
    `Reprendre ${now === before ? "inchangé" : "changé"} après l'arrêt à ${stopAt.toFixed(0)} s`,
  );
  await closeMedia(page);
}

async function seriesSteps(app) {
  const { page } = app;
  step = "série : choix";
  const pick = await pickTitle(page, "tv", SERIES);
  fact("seriesTried", pick.tried);
  if (!pick.title)
    throw new Error(
      `aucune série hors Emby avec IMDb (${pick.tried.join(", ")})`,
    );
  step = "série : page";
  await openFromSearch(page, pick);
  check("series_found_outside_emby", true, `« ${pick.title} » (${pick.year})`);
  await watchButton(page).waitFor({ timeout: 10000 });

  step = "série : sélecteur";
  await watchButton(page).click();
  await picker(page).waitFor({ state: "visible", timeout: 10000 });
  const season = picker(page).locator("select");
  const seasonLists = await season.count();
  const opened = await pickerFocus(page);
  check(
    "picker_opens_on_season_list",
    seasonLists === 1 && opened.inPicker && opened.tag === "SELECT",
    `${seasonLists} liste(s) de saisons, focus ${opened.tag}`,
  );
  await page.keyboard.press("Escape");
  await picker(page).waitFor({ state: "detached", timeout: 10000 });
  const back = await focusInfo(page);
  check(
    "picker_escape_focus_on_button",
    back.text === WATCH,
    `focus ${back.tag} « ${back.text} »`,
  );

  step = "série : saison 1, épisode 1";
  await watchButton(page).click();
  await picker(page).waitFor({ state: "visible", timeout: 10000 });
  await season.selectOption("1");
  const grid = picker(page).getByRole("list", { name: "Saison 1" });
  await grid.waitFor({ timeout: 5000 });
  check("season_1_chosen", (await season.inputValue()) === "1");
  await grid.getByRole("button", { name: "Épisode 1", exact: true }).click();
  await picker(page).waitFor({ state: "detached", timeout: 10000 });

  step = "série : panneau S1E1";
  await sourcesDialog(page).waitFor({ state: "visible", timeout: 10000 });
  const settled = await waitSettled(page);
  fact(
    "episodeAddons",
    settled.addons.map((a) => ({
      name: a.name,
      state: a.state,
      streams: a.streams,
    })),
  );
  check(
    "episode_addons_with_streams",
    settled.addons.some((a) => a.state === "ok"),
    addonsSummary(settled),
  );
  const leaks = await dialogLeaks(page);
  check(
    "episode_dialog_without_addresses",
    !leaks.address && !leaks.manifest && !leaks.debridHost,
  );
  const shot = await capture(app, out, "regarder-maintenant-s1e1", secrets);
  check(
    "capture_episode_panel",
    shot.printed && shot.distinctColors > 20,
    `${basename(shot.file)}, ${shot.distinctColors} couleurs`,
  );
  await closePanel(page);
  const after = await focusInfo(page);
  check(
    "episode_escape_focus_on_button",
    after.text === WATCH,
    `focus ${after.tag} « ${after.text} »`,
  );
  await closeMedia(page);
}

// ---- run --------------------------------------------------------------------------

let app = null;
let authKey = null;
/** What the end must undo. `started`: the vault had no entry of ours. */
const state = { started: false, emby: false, stremio: false, ur: false };

const alive = () => app && app.proc.exitCode === null;

/** Always: Stremio, then Request, then Emby signed out. */
async function finale() {
  try {
    step = "fin : Stremio";
    if (!alive())
      app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
    const { page } = app;
    await signOutStremio(page, state, check);
    const signedIn = state.emby && (await startScreen(page)) === "signedIn";
    if (signedIn && state.ur) {
      step = "fin : Request";
      await invoke(page, "ur_sign_out");
      state.ur = false;
      check("request_signed_out", true);
    }
    step = "fin : déconnexion Emby";
    if (signedIn || vaultTargets(/Viewer-debug/i) > 0) {
      await toNav(page).catch(() => {});
      if ((await startScreen(page)) === "signedIn") await signOut(page);
      state.emby = false;
      check("emby_signed_out", await signInHeading(page).isVisible());
    }
  } catch (e) {
    check("finale_error", false, `${step} : ${firstLine(e)}`);
    await lastStremioSignOut(app);
    if (state.ur && alive())
      await app.page
        .evaluate(() => window.__viewer.invoke("ur_sign_out"))
        .catch(() => {});
  }
}

try {
  if (!stremio) throw new Error("ST_EMAIL_FILE et ST_PASS_FILE sont requis");
  if (!urUrl) throw new Error("VIEWER_UR_URL absent de debug.env");
  if (vaultTargets(/Viewer-debug|stremio/i) > 0)
    throw new Error(
      "le coffre a déjà une entrée Viewer-debug ou stremio : rien n'est lancé",
    );
  state.started = true;
  if (argv.includes("--build")) buildDebug();
  if (!existsSync(exe)) throw new Error(`build absent : ${exe} (--build)`);

  app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
  const { page } = app;
  await readyApp(page, check);

  // 1. Emby, then Stremio from Réglages, then Demander.
  authKey = await signInEmbyAndStremio(page, {
    account,
    stremio,
    check,
    state,
    secrets,
    onStep: (s) => {
      step = s;
    },
  });
  step = "Demander";
  await openRequests(page);
  check("request_ready", true);

  await filmSteps(app);
  await seriesSteps(app);
} catch (e) {
  check("scenario_error", false, `${step} : ${firstLine(e)}`);
} finally {
  if (state.started) await finale();
  if (app) await app.close().catch(() => {});
  app = null;
  if (state.started)
    checkLogAndVault(check, {
      logFile,
      allowedUrls: [account.url, urUrl],
      authKey,
      secrets,
    });
  authKey = null;
  check("work_folder_removed", await removeWork(work));
  finish(out);
}
