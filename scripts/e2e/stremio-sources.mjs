// « Autres sources » on the Go + Wails debug build, driven over CDP like
// stremio-settings (no OS input; the window stays off-screen, no sound with
// VIEWER_E2E_SILENT, captured with PrintWindow on its own HWND).
//
// Steps: Emby sign-in with the debug.env account → Stremio sign-in from
// Réglages → a film of the Films library with an IMDb id, no resume point, not
// played → « Autres sources » shown → the panel: a modal dialog with the focus
// inside, addon sections filled as each addon answers, at least three with
// streams, no address in the dialog → Escape closes it, focus back on the
// button → the first stream of the first addon with streams plays (else the
// first of up to three other addons): `fileLoaded` on `player://event` within
// 30 s, the position moves 3 s, badge « Stremio », no chapters button, no
// « Qualité » in the options → played past the 10 s progress interval after a
// seek to 40 % → closed: no Emby resume point, played state or « Reprendre »
// entry for the film, back on the page with the focus on the button → an
// episode of a series with an IMDb id: at least one addon with streams →
// always at the end: Stremio sign-out, no `stremio` vault entry, the button
// gone from a fresh film page, Emby sign-out. The app log is checked for
// manifest addresses, stream addresses and the authKey.
//
// Accounts as stremio-settings: the Emby account from debug.env, the Stremio
// e-mail and password from the files named by ST_EMAIL_FILE / ST_PASS_FILE
// (required here); none of them reaches the console, report.json or a capture.
// Only titles and addon names are printed, never an id or an address.
//
// The app runs with APPDATA, LOCALAPPDATA and its log in a temporary folder,
// deleted at the end; the Windows vault is shared, so the run refuses to start
// over an existing Viewer-debug or stremio entry and always ends signed out.
//
//   node scripts/e2e/stremio-sources.mjs [--build] [--out <dir>]
//
// --build  npm run build, then a debug `go build` (app.mjs buildDebug)
// --out    folder of the captures and report.json
//          (default: <tmp>/viewer-stremio-sources-<timestamp>)
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { buildDebug, debugExe as exe, launch } from "./app.mjs";
import {
  cards,
  detailTitle,
  episodeTitle,
  invoke,
  libraryNav,
  signIn,
  signInHeading,
  signOut,
  startScreen,
} from "./scenario.mjs";
import {
  capture,
  openSettings,
  readAccounts,
  readAuthKey,
  removeWork,
  scrubbedReport,
  scrubber,
  signInFromSettings,
  vaultTargets,
  waitForSettingsState,
} from "./stremio.mjs";

const argv = process.argv.slice(2);
const outArg = argv.indexOf("--out");
const stamp = new Date()
  .toISOString()
  .replace(/[-:]/g, "")
  .replace("T", "-")
  .slice(0, 15);
const out =
  outArg >= 0
    ? argv[outArg + 1]
    : join(tmpdir(), `viewer-stremio-sources-${stamp}`);
const PORT = 9345;
const SOURCE_FAILED = "Ce lien ne répond plus, choisis une autre source.";
/** Addons that hold no debrid streams: absent, empty or unavailable is fine. */
const NON_STREAM_ADDONS = ["TMDB Collections", "Local Files"];
const MIN_ADDONS_WITH_STREAMS = 3;
/** First addon with streams, then up to this many others. */
const OTHER_ATTEMPTS = 3;

mkdirSync(out, { recursive: true });
const work = mkdtempSync(join(tmpdir(), "viewer-e2e-stremio-sources-"));
const logFile = join(work, "logs", "viewer.log");
const appEnv = {
  APPDATA: join(work, "Roaming"),
  LOCALAPPDATA: join(work, "Local"),
  VIEWER_E2E_LOG_DIR: join(work, "logs"),
};

const { account, urUrl, stremio, secrets } = readAccounts();
const { scrub, firstLine } = scrubber(secrets);
const { report, check, finish } = scrubbedReport(scrub, {
  build: "debug (coffre Viewer-debug)",
});
report.facts = {};
const fact = (name, value) => {
  report.facts[name] = JSON.parse(scrub(JSON.stringify(value ?? null)));
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** The step running, named in a scenario error. */
let step = "démarrage";

async function poll(fn, accept, timeoutMs, stepMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (accept(last)) return last;
    await sleep(stepMs);
  }
  return last;
}

// ---- navigation ---------------------------------------------------------------

/** Escape out of detail pages and the player until the navigation shows. */
async function toNav(page) {
  for (let i = 0; i < 5 && !(await libraryNav(page).isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
  }
  await libraryNav(page).waitFor({ timeout: 10000 });
}

/** A library of the navigation by its kind label (« Films », « Séries »). */
async function openLibrary(page, kind) {
  await toNav(page);
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  const at = kinds.indexOf(kind);
  if (at < 0) throw new Error(`pas de bibliothèque ${kind}`);
  await libraries.nth(at).click();
  await cards(page).first().waitFor({ timeout: 20000 });
}

/** The film page of a card, settled (actions rendered). */
async function openFilm(page, id) {
  await page.locator(`ul.grid button.card[data-item-id="${id}"]`).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => {
      const s = document.querySelector("section.detail");
      return (
        s?.getAttribute("aria-busy") === "false" &&
        !!s.querySelector(".play-actions")
      );
    },
    null,
    { timeout: 20000 },
  );
}

const filmPage = (page) =>
  page.locator("section.detail", { has: detailTitle(page) });
const episodePage = (page) =>
  page.locator("section.detail", { has: episodeTitle(page) });
const sourcesButton = (scope) =>
  scope.getByRole("button", { name: "Autres sources", exact: true });

/** Text of the focused element and whether it is « Autres sources ». */
const focusInfo = (page) =>
  page.evaluate(() => {
    const a = document.activeElement;
    return {
      text: a?.textContent?.trim() ?? "",
      tag: a?.tagName ?? "",
      inDialog: !!a?.closest("dialog[aria-labelledby=sources-title]"),
    };
  });

// ---- the panel ------------------------------------------------------------------

const dialog = (page) => page.locator("dialog[aria-labelledby=sources-title]");

/** Every addon section with its state, read from the dialog. */
const panelState = (page) =>
  page.evaluate(() => {
    const d = document.querySelector("dialog[aria-labelledby=sources-title]");
    if (!d?.open) return { open: false, addons: [] };
    const addons = [...d.querySelectorAll("section.addon")].map((s) => {
      const name = s.querySelector("h3")?.textContent?.trim() ?? "";
      const streams = s.querySelectorAll("button.stream").length;
      const note = s.querySelector("p.muted")?.textContent?.trim() ?? "";
      const state =
        streams > 0
          ? "ok"
          : note === "Recherche…"
            ? "loading"
            : note === "Aucun flux."
              ? "empty"
              : "error";
      return { name, state, streams, note: state === "error" ? note : "" };
    });
    return {
      open: true,
      busy: d.getAttribute("aria-busy") === "true",
      error: d.querySelector(".list .state")?.textContent?.trim() ?? null,
      notice: d.querySelector(".notice")?.textContent?.trim() ?? null,
      unsupported: d.querySelector(".foot .muted")?.textContent?.trim() ?? null,
      addons,
    };
  });

/**
 * Samples the panel until every addon settled; `progressive` when a sample
 * showed answered and still-loading sections together.
 */
async function waitSettled(page, timeoutMs = 45000) {
  const t0 = Date.now();
  const settledAt = {};
  let progressive = false;
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    last = await panelState(page);
    if (last.error) break;
    const states = last.addons.map((a) => a.state);
    for (const a of last.addons)
      if (a.state !== "loading" && !(a.name in settledAt))
        settledAt[a.name] = Date.now() - t0;
    if (states.includes("loading") && states.some((s) => s !== "loading"))
      progressive = true;
    if (states.length > 0 && !states.includes("loading")) break;
    await sleep(100);
  }
  const times = Object.values(settledAt);
  return {
    ...last,
    progressive,
    firstMs: times.length ? Math.min(...times) : null,
    lastMs: times.length ? Math.max(...times) : null,
  };
}

/** Clicks « Autres sources » in `scope` and waits for the dialog. */
async function openPanel(page, scope) {
  await sourcesButton(scope).click();
  await dialog(page).waitFor({ state: "visible", timeout: 10000 });
}

async function closePanel(page) {
  await page.keyboard.press("Escape");
  await dialog(page).waitFor({ state: "detached", timeout: 10000 });
}

/** The dialog's markup: no address, manifest or debrid host, booleans only. */
const dialogLeaks = (page) =>
  dialog(page).evaluate((d) => {
    const html = d.outerHTML;
    return {
      address: /https?:|:\/\//i.test(html),
      manifest: /manifest\.json/i.test(html),
      debridHost:
        /[a-z0-9-]*debrid[a-z0-9-]*\.(com|net|org|io|app|fr|link|it|me|cc|xyz|tv)\b/i.test(
          html,
        ),
    };
  });

// ---- the player -------------------------------------------------------------------

const decision = (page) => page.locator(".player .decision");

/** Records the app's own `player://event` stream in the page (window.__st). */
async function watchPlayer(page) {
  await page.evaluate(async () => {
    if (!window.__st) {
      const rt = await import("/wails/runtime.js");
      rt.Events.On("player://event", (e) => {
        const d = e.data;
        const s = window.__st;
        if (d.type === "fileLoaded") s.loaded.push(Date.now());
        if (d.type === "timePos" && d.seconds != null)
          s.times.push([Date.now(), d.seconds]);
        if (d.type === "duration") s.duration = d.seconds;
        if (d.type === "endFile") s.ends.push([d.reason, d.error ?? null]);
        if (s.times.length > 400) s.times.shift();
      });
    }
    window.__st = { loaded: [], times: [], ends: [], duration: null };
  });
}
const events = (page) => page.evaluate(() => window.__st);
const command = (page, c) => invoke(page, "player_command", { command: c });

/** Escape until the player is gone. */
async function leavePlayer(page) {
  for (let i = 0; i < 3 && (await decision(page).count()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(600);
  }
  await decision(page).waitFor({ state: "detached", timeout: 15000 });
}

/**
 * Plays the first stream of `addon` from the open panel. Success: `fileLoaded`
 * within 30 s and 3 s of position. A failed link brings the panel back with
 * its message; a stalled one is closed here.
 */
async function tryAddon(page, addon) {
  await watchPlayer(page);
  const t0 = Date.now();
  await dialog(page)
    .locator("section.addon", {
      has: page.locator("h3", { hasText: addon }),
    })
    .locator("button.stream")
    .first()
    .click();
  const outcome = await poll(
    async () => {
      const ev = await events(page);
      const panel = await panelState(page);
      return { ev, panel, player: (await decision(page).count()) > 0 };
    },
    ({ ev, panel, player }) =>
      ev.loaded.length > 0 ||
      ev.ends.some(([r]) => r === "error") ||
      (panel.open && panel.notice) ||
      (!player && !panel.open && Date.now() - t0 > 5000),
    30000,
    200,
  );
  const loadedMs = outcome.ev.loaded[0] ? outcome.ev.loaded[0] - t0 : null;
  if (loadedMs === null) {
    if (await decision(page).count()) await leavePlayer(page);
    const reason = outcome.panel.notice
      ? outcome.panel.notice === SOURCE_FAILED
        ? "lien qui ne répond plus"
        : `refusé : ${outcome.panel.notice}`
      : outcome.ev.ends.length
        ? `fin ${outcome.ev.ends.map(([r]) => r).join(",")}`
        : "pas de fileLoaded en 30 s";
    return { ok: false, reason };
  }
  // The position moves 3 s past the first one reported.
  const moved = await poll(
    () => events(page),
    (s) => s.times.length > 1 && s.times.at(-1)[1] - s.times[0][1] >= 3,
    30000,
  );
  const first = moved.times[0]?.[1];
  const last = moved.times.at(-1)?.[1];
  if (!(last - first >= 3)) {
    await leavePlayer(page);
    return {
      ok: false,
      loadedMs,
      reason: `position ${first?.toFixed(1)} → ${last?.toFixed(1)} s`,
    };
  }
  return { ok: true, loadedMs, from: first, to: last };
}

/** Emby's view of the film: resume point, played, in « Reprendre ». */
async function embyTrace(page, id) {
  const d = await invoke(page, "emby_item_detail", { itemId: id });
  const cw = await invoke(page, "emby_continue_watching");
  return {
    resume: d.resume?.positionSeconds ?? null,
    played: d.played,
    continueWatching: cw.some((w) => w.id === id),
  };
}
const sameTrace = (a, b) =>
  a.resume === b.resume &&
  a.played === b.played &&
  a.continueWatching === b.continueWatching;
const traceText = (t) =>
  `reprise ${t.resume === null ? "aucune" : `${t.resume.toFixed(0)} s`}, vu ${t.played}, dans Reprendre ${t.continueWatching}`;

// ---- steps ------------------------------------------------------------------------

/** A film of the first Films cards with an IMDb id and nothing on Emby yet. */
async function findFilm(page) {
  await openLibrary(page, "Films");
  const n = Math.min(24, await cards(page).count());
  for (let i = 0; i < n; i++) {
    const id = await cards(page).nth(i).getAttribute("data-item-id");
    const trace = await embyTrace(page, id);
    if (trace.resume !== null || trace.played || trace.continueWatching)
      continue;
    try {
      const s = await invoke(page, "stremio_sources", { itemId: id });
      if ((s.addons ?? []).length >= MIN_ADDONS_WITH_STREAMS)
        return { id, trace };
    } catch (e) {
      if (!/noImdb/.test(String(e?.message))) throw e;
    }
  }
  return null;
}

async function filmSteps(app) {
  const { page } = app;
  step = "film";
  const film = await findFilm(page);
  if (!film) throw new Error("aucun film avec IMDb, sans reprise ni vu");
  state.filmId = film.id;
  await openFilm(page, film.id);
  // The title may be a logo: its alt text then.
  const title = await detailTitle(page).evaluate(
    (e) => e.textContent.trim() || e.querySelector("img")?.alt || "",
  );
  check("film_found", true, `« ${title} »`);
  const button = sourcesButton(filmPage(page));
  await button.waitFor({ timeout: 10000 });
  check("button_shown_signed_in", await button.isVisible());

  // 2. The panel.
  step = "panneau du film";
  await openPanel(page, filmPage(page));
  const focus = await focusInfo(page);
  const modal = await dialog(page).evaluate(
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
      note: a.note,
    })),
  );
  fact("unsupportedNote", settled.unsupported);
  const summary = settled.addons
    .map((a) => `${a.name}: ${a.state === "ok" ? a.streams : a.state}`)
    .join(" ; ");
  check(
    "addons_progressive",
    settled.progressive || settled.lastMs - settled.firstMs > 300,
    `premier ${settled.firstMs} ms, dernier ${settled.lastMs} ms, état mixte vu ${settled.progressive}`,
  );
  const withStreams = settled.addons.filter((a) => a.state === "ok");
  check(
    "addons_with_streams",
    withStreams.length >= MIN_ADDONS_WITH_STREAMS &&
      !settled.addons.some((a) => a.state === "loading"),
    summary,
  );
  const others = NON_STREAM_ADDONS.map((n) => {
    const a = settled.addons.find((x) => x.name.startsWith(n));
    return { name: n, state: a ? a.state : "absent" };
  });
  check(
    "non_stream_addons_harmless",
    others.every((o) => o.state !== "loading" && o.state !== "ok") &&
      withStreams.length > 0,
    others.map((o) => `${o.name}: ${o.state}`).join(", "),
  );
  const leaks = await dialogLeaks(page);
  check(
    "dialog_without_addresses",
    !leaks.address && !leaks.manifest && !leaks.debridHost,
    `adresse ${leaks.address}, manifest.json ${leaks.manifest}, hôte debrid ${leaks.debridHost}`,
  );
  const shotPanel = await capture(app, out, "autres-sources", secrets);
  check(
    "capture_panel",
    shotPanel.printed && shotPanel.distinctColors > 20,
    `${basename(shotPanel.file)}, ${shotPanel.distinctColors} couleurs`,
  );

  // 3. Escape closes, focus back on the button.
  await closePanel(page);
  const back = await focusInfo(page);
  check(
    "escape_closes_focus_on_button",
    back.text === "Autres sources",
    `focus ${back.tag} « ${back.text} »`,
  );

  step = "lecture";
  // 4. Playback: the first addon with streams, else up to 3 others.
  const before = await embyTrace(page, film.id);
  await openPanel(page, filmPage(page));
  let panel = await waitSettled(page);
  const candidates = panel.addons
    .filter((a) => a.state === "ok")
    .map((a) => a.name)
    .slice(0, 1 + OTHER_ATTEMPTS);
  const attempts = [];
  let played = null;
  for (const addon of candidates) {
    if (!(await dialog(page).isVisible())) {
      await openPanel(page, filmPage(page));
    }
    panel = await waitSettled(page);
    if (!panel.addons.some((a) => a.name === addon && a.state === "ok")) {
      attempts.push({ addon, ok: false, reason: "plus de flux" });
      continue;
    }
    const r = await tryAddon(page, addon);
    attempts.push({ addon, ...r });
    if (r.ok) {
      played = { addon, ...r };
      break;
    }
  }
  fact("playAttempts", attempts);
  check(
    "stremio_playback",
    played !== null,
    attempts
      .map(
        (a) =>
          `${a.addon}: ${a.ok ? `lu (fileLoaded en ${a.loadedMs} ms, ${a.from.toFixed(1)} → ${a.to.toFixed(1)} s)` : `échec (${a.reason})`}`,
      )
      .join(" ; "),
  );
  if (!played) {
    if (await dialog(page).isVisible()) await closePanel(page);
    return;
  }
  state.playing = true;
  check(
    "file_loaded_within_30s",
    played.loadedMs <= 30000,
    `${played.loadedMs} ms`,
  );
  const badge = (await decision(page).textContent())?.trim();
  check("badge_stremio", badge === "Stremio", badge);

  // No Emby chapters or quality for a Stremio stream.
  const player = page.locator(".player");
  await page.mouse.move(600, 400);
  await page.mouse.move(620, 420);
  const chapters = await player
    .getByRole("button", { name: "Chapitres", exact: true })
    .count();
  await player
    .getByRole("button", { name: "Options de lecture", exact: true })
    .click();
  const options = player.getByRole("dialog", { name: "Options de lecture" });
  await options.waitFor({ timeout: 5000 });
  const legends = await options
    .locator("legend")
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));
  check(
    "no_chapters_no_quality",
    chapters === 0 && legends.length > 0 && !legends.includes("Qualité"),
    `bouton Chapitres ${chapters}, options : ${legends.join(", ")}`,
  );
  await page.keyboard.press("Escape");
  await options.waitFor({ state: "detached", timeout: 5000 });

  // 5. Past Emby's 10 s progress interval, far into the film: any report
  // would leave a resume point.
  const duration = (await events(page)).duration;
  if (duration > 120) {
    const target = Math.round(duration * 0.4);
    await command(page, { type: "seekAbsolute", seconds: target });
    await poll(
      () => events(page),
      (s) => (s.times.at(-1)?.[1] ?? 0) > target + 1,
      30000,
    );
  }
  const holdFrom = (await events(page)).times.at(-1)?.[1] ?? 0;
  await poll(
    () => events(page),
    (s) => (s.times.at(-1)?.[1] ?? 0) > holdFrom + 12,
    40000,
  );
  await page.mouse.move(640, 360);
  await page.mouse.move(650, 370);
  const shotPlay = await capture(app, out, "lecture-stremio", secrets);
  check(
    "capture_playing",
    shotPlay.printed && shotPlay.distinctColors > 50,
    `${basename(shotPlay.file)}, ${shotPlay.distinctColors} couleurs`,
  );
  const stopAt = (await events(page)).times.at(-1)?.[1] ?? 0;
  fact("stremioStop", {
    duration,
    holdFrom: Math.round(holdFrom),
    stopAt: Math.round(stopAt),
  });

  step = "fermeture du lecteur";
  // 6. Close the player: back on the page, focus on the button.
  await leavePlayer(page);
  state.playing = false;
  const onPage = await detailTitle(page).isVisible();
  const after = await focusInfo(page);
  check(
    "player_closed_focus_restored",
    onPage && after.text === "Autres sources",
    `fiche ${onPage}, focus ${after.tag} « ${after.text} »`,
  );
  const trace = await poll(
    () => embyTrace(page, film.id),
    (t) => !sameTrace(t, before),
    15000,
    1500,
  );
  check(
    "no_emby_progress",
    sameTrace(trace, before),
    `avant : ${traceText(before)} ; après (arrêt à ${stopAt.toFixed(0)} s) : ${traceText(trace)}`,
  );
  state.embyTraceChanged = !sameTrace(trace, before);
}

/** An episode (season > 0) of a series with an IMDb id, from the API. */
async function findEpisode(page) {
  await openLibrary(page, "Séries");
  const n = Math.min(15, await cards(page).count());
  for (let i = 0; i < n; i++) {
    const seriesId = await cards(page).nth(i).getAttribute("data-item-id");
    const seasons = (await invoke(page, "emby_seasons", { seriesId })).filter(
      (s) => (s.index ?? 0) > 0,
    );
    if (!seasons.length) continue;
    const season = seasons[0];
    const eps = await invoke(page, "emby_episodes", {
      seriesId,
      seasonId: season.id,
      startIndex: 0,
      limit: 50,
    });
    const ep = eps.items.find((e) => e.seasonIndex === season.index);
    if (!ep) continue;
    try {
      const s = await invoke(page, "stremio_sources", { itemId: ep.id });
      if ((s.addons ?? []).length > 0) return { card: i, season, ep };
    } catch (e) {
      if (!/noImdb/.test(String(e?.message))) throw e;
    }
  }
  return null;
}

async function episodeSteps(app) {
  const { page } = app;
  step = "épisode";
  const target = await findEpisode(page);
  if (!target) throw new Error("aucun épisode de série avec IMDb");
  step = "épisode : carte de la série";
  await cards(page).nth(target.card).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await page
    .locator("ol.list li.episode:not(.skeleton)")
    .first()
    .waitFor({ timeout: 20000 });
  const pressed = await page
    .locator('.seasons button[aria-pressed="true"]')
    .evaluate((b) => b.firstChild.textContent.trim())
    .catch(() => null);
  step = `épisode : saison (« ${pressed} » choisie, « ${target.season.name} » voulue)`;
  // One season: no selector, it is shown already.
  if (pressed !== null && pressed !== target.season.name)
    await page
      .getByRole("group", { name: "Saisons" })
      .getByRole("button", { name: target.season.name })
      .first()
      .click();
  const row = page.locator(
    `ol.list li.episode[data-episode-id="${target.ep.id}"]`,
  );
  step = "épisode : ligne";
  await row.waitFor({ timeout: 20000 });
  await row.locator("button.open").click();
  step = "épisode : page";
  await episodeTitle(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => {
      const s = document
        .querySelector("#episode-title")
        ?.closest("section.detail");
      return (
        s?.getAttribute("aria-busy") === "false" &&
        !!s.querySelector(".play-actions")
      );
    },
    null,
    { timeout: 20000 },
  );
  // The series name may be a logo: its alt text then.
  const seriesName = await episodePage(page)
    .locator("p.series")
    .evaluate((e) => e.textContent.trim() || e.querySelector("img")?.alt || "");
  const position = (
    await episodePage(page).locator(".position").textContent()
  )?.trim();
  check("episode_found", true, `${seriesName} — ${position}`);
  await openPanel(page, episodePage(page));
  const settled = await waitSettled(page);
  const withStreams = settled.addons.filter((a) => a.state === "ok");
  fact(
    "episodeAddons",
    settled.addons.map((a) => ({
      name: a.name,
      state: a.state,
      streams: a.streams,
    })),
  );
  check(
    "episode_streams",
    withStreams.length >= 1,
    settled.addons
      .map((a) => `${a.name}: ${a.state === "ok" ? a.streams : a.state}`)
      .join(" ; "),
  );
  const leaks = await dialogLeaks(page);
  check(
    "episode_dialog_without_addresses",
    !leaks.address && !leaks.manifest && !leaks.debridHost,
  );
  const shot = await capture(app, out, "autres-sources-episode", secrets);
  check(
    "capture_episode_panel",
    shot.printed && shot.distinctColors > 20,
    `${basename(shot.file)}, ${shot.distinctColors} couleurs`,
  );
  await closePanel(page);
  const back = await focusInfo(page);
  check(
    "episode_escape_focus_on_button",
    back.text === "Autres sources",
    `focus ${back.tag} « ${back.text} »`,
  );
}

// ---- run --------------------------------------------------------------------------

let app = null;
let authKey = null;
/** What the end must undo. `started`: the vault had no entry of ours. */
const state = {
  started: false,
  emby: false,
  stremio: false,
  playing: false,
  filmId: null,
  embyTraceChanged: false,
};

const alive = () => app && app.proc.exitCode === null;
const relaunch = async () => {
  if (!alive())
    app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
};

/** Always: Stremio sign-out, vault empty of it, button gone, Emby sign-out. */
async function finale() {
  try {
    step = "fin : Stremio";
    await relaunch();
    const { page } = app;
    if (await decision(page).count()) await leavePlayer(page);
    if (state.stremio || vaultTargets(/stremio/i) > 0) {
      await invoke(page, "stremio_sign_out");
      state.stremio = false;
    }
    step = "fin : coffre";
    const left = vaultTargets(/stremio/i) > 0;
    console.log(`cmdkey stremio target present: ${left}`);
    check("stremio_signed_out_vault_empty", !left, String(left));
    if (state.emby) await toNav(page).catch(() => {});
    const signedIn = state.emby && (await startScreen(page)) === "signedIn";
    if (signedIn && state.filmId) {
      step = "fin : bouton absent";
      // A fresh page reads the Stremio state again.
      await openLibrary(page, "Films");
      await openFilm(page, state.filmId);
      await page.waitForTimeout(2000);
      const count = await sourcesButton(filmPage(page)).count();
      check("button_gone_signed_out", count === 0, `${count} bouton(s)`);
    }
    if (signedIn && state.embyTraceChanged) {
      // A report reached Emby after all: leave the account as found.
      await invoke(page, "emby_set_played", {
        itemId: state.filmId,
        played: false,
      }).catch(() => {});
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
    // The key must not outlive the run.
    if (vaultTargets(/stremio/i) > 0 && alive())
      await app.page
        .evaluate(() => window.__viewer.invoke("stremio_sign_out"))
        .catch(() => {});
  }
}

try {
  if (!stremio) throw new Error("ST_EMAIL_FILE et ST_PASS_FILE sont requis");
  if (vaultTargets(/Viewer-debug|stremio/i) > 0)
    throw new Error(
      "le coffre a déjà une entrée Viewer-debug ou stremio : rien n'est lancé",
    );
  state.started = true;
  if (argv.includes("--build")) buildDebug();
  if (!existsSync(exe)) throw new Error(`build absent : ${exe} (--build)`);

  app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
  const { page } = app;
  const info = await invoke(page, "app_info");
  check("libmpv_found", info.libmpvFound === true);
  if (!info.libmpvFound)
    throw new Error(
      "libmpv absent : vendor/mpv/libmpv-2.dll (fetch-libmpv.ps1)",
    );
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });

  // 1. Emby, then Stremio from Réglages.
  check("first_run_signed_out", (await startScreen(page)) === "signedOut");
  step = "connexion Emby";
  await signIn(page, account, account.password);
  state.emby = true;
  await libraryNav(page).waitFor({ timeout: 30000 });
  step = "connexion Stremio";
  await openSettings(page);
  await waitForSettingsState(page, "Déconnecté", 20000);
  state.stremio = true;
  await signInFromSettings(page, stremio);
  check("stremio_signed_in", true);
  authKey = readAuthKey();
  if (authKey) secrets.push(authKey);
  check("auth_key_in_vault", authKey !== null);

  await filmSteps(app);
  await episodeSteps(app);
} catch (e) {
  check("scenario_error", false, `${step} : ${firstLine(e)}`);
} finally {
  if (state.started) await finale();
  if (app) await app.close().catch(() => {});
  app = null;
  if (state.started) {
    if (!existsSync(logFile)) check("log_written", false, "journal absent");
    else {
      const log = readFileSync(logFile, "utf8");
      const allowed = new Set(
        [account.url, urUrl]
          .filter(Boolean)
          .flatMap((u) => {
            try {
              return [new URL(u).host];
            } catch {
              return [];
            }
          })
          .concat(["wails.localhost", "localhost", "127.0.0.1"]),
      );
      const foreign = (log.match(/https?:\/\/[^\s"'\\<>]+/gi) ?? []).filter(
        (u) => {
          try {
            return !allowed.has(new URL(u).host);
          } catch {
            return true;
          }
        },
      ).length;
      check(
        "log_without_stream_addresses_or_key",
        !/manifest\.json/i.test(log) &&
          foreign === 0 &&
          (authKey === null || !log.includes(authKey)),
        `manifest.json ${/manifest\.json/i.test(log)}, ${foreign} adresse(s) hors Emby, authKey ${authKey ? log.includes(authKey) : "non lu"}`,
      );
      const leaked = secrets.filter((s) => log.includes(s)).length;
      check("log_without_account_values", leaked === 0, `${leaked} valeur(s)`);
      fact(
        "addonsUnavailableInLog",
        log
          .split(/\r?\n/)
          .filter((l) => l.includes("Stremio addon streams unavailable"))
          .length,
      );
    }
    check("no_stremio_vault_entry", vaultTargets(/stremio/i) === 0);
    check(
      "no_debug_vault_entry",
      vaultTargets(/Viewer-debug/i) === 0,
      "Viewer-debug",
    );
  }
  authKey = null;
  check("work_folder_removed", await removeWork(work));
  finish(out);
}
