// Gate 4 baselines on the real Emby test account (PRODUCT.md: establish baselines
// before setting targets): what measure-startup does not cover. Three launches:
// - empty artwork cache: whole films grid scrolled, cache misses, memory;
// - same cache: same scroll and its hit rate, navigation latency, playback start
//   and stop of films spread across the grid and the server's decision for each,
//   memory before and after, then 60 s of Direct Play with dropped frames and seeks;
// - bitrate capped by VIEWER_E2E_MAX_BITRATE: the same on a transcode.
// Not a pass/fail gate: a check fails only if a film does not start, or if the
// account's "continue watching" changes.
// Debug build, window off-screen, audio to ao=null: see the evidence file for what
// this does not show. Long sessions go back to 0 so no resume point is left.
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  diagnostics,
  homeHeading,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, finish } = createReport("measure-gate4");
const cacheDir = mkdtempSync(join(tmpdir(), "viewer-e2e-measure-"));
const POSTERS = 12;
const NAV_RUNS = 5;
const FILM_SAMPLES = 12;
const SESSION_MS = 60000;
const SEEK_FRACTIONS = [0.25, 0.5, 0.1];

const percentile = (xs, p) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
};
const median = (xs) => percentile(xs, 0.5);
const list = (xs) => `médiane ${median(xs)} ms (${xs.join(", ")})`;

/** Working set and private bytes of the app and all its descendants, in Mio. */
function memory(pid) {
  const ps =
    "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,WorkingSetSize,PrivatePageCount | ConvertTo-Json -Compress";
  const r = spawnSync("powershell", ["-NoProfile", "-Command", ps], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (r.status !== 0) throw new Error(`memory read failed: ${r.stderr}`);
  const all = JSON.parse(r.stdout);
  const tree = new Set([pid]);
  for (let grew = true; grew; ) {
    grew = false;
    for (const p of all) {
      if (tree.has(p.ParentProcessId) && !tree.has(p.ProcessId)) {
        tree.add(p.ProcessId);
        grew = true;
      }
    }
  }
  const mine = all.filter((p) => tree.has(p.ProcessId));
  const mib = (n) => Math.round(n / (1024 * 1024));
  const sum = (key, xs = mine) => mib(xs.reduce((a, p) => a + p[key], 0));
  const app = mine.filter((p) => p.ProcessId === pid);
  return {
    processes: mine.length,
    privateMib: sum("PrivatePageCount"),
    workingSetMib: sum("WorkingSetSize"),
    appPrivateMib: sum("PrivatePageCount", app),
  };
}
const showMemory = (m) =>
  `${m.privateMib} Mio privés dont ${m.appPrivateMib} pour l'exécutable (mpv compris), ensemble de travail ${m.workingSetMib} Mio, ${m.processes} processus`;

const invoke = (page, command, args = {}) =>
  page.evaluate(({ command, args }) => window.__viewer.invoke(command, args), {
    command,
    args,
  });

/** Clicks inside the page, so CDP is not timed, until the awaited state is painted. */
const timedClick = (page, click, until) =>
  page.evaluate(
    async ({ click, until }) => {
      const element = document.querySelectorAll(click.selector)[
        click.index ?? 0
      ];
      if (!element) throw new Error(`nothing to click: ${click.selector}`);
      const reached = () => {
        if (until.absent && document.querySelector(until.absent)) return false;
        if (until.present && !document.querySelector(until.present))
          return false;
        if (!until.images) return true;
        const imgs = [...document.querySelectorAll(until.images)].slice(
          0,
          until.count,
        );
        return (
          imgs.length === until.count &&
          imgs.every((i) => i.complete && i.naturalWidth > 0)
        );
      };
      const t0 = performance.now();
      element.click();
      while (!reached()) {
        if (performance.now() - t0 > 30000)
          throw new Error(`not reached: ${JSON.stringify(until)}`);
        await new Promise(requestAnimationFrame);
      }
      return Math.round(performance.now() - t0);
    },
    { click, until },
  );

const libraryButton = async (page, kind) => {
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  return { selector: "nav ul.libraries button", index: kinds.indexOf(kind) };
};

const FILMS_READY = { images: "ul.grid button.card img", count: POSTERS };

/**
 * Scrolls the grid to its end like a wheel, 120 px a frame, while pages load: frame
 * times while moving, cards at the end, then a pause for the last images.
 */
const scrollGrid = (page) =>
  page.evaluate(async () => {
    let sc = document.querySelector("ul.grid");
    while (sc && !/(auto|scroll)/.test(getComputedStyle(sc).overflowY))
      sc = sc.parentElement;
    if (!sc) throw new Error("no scrolling parent of the grid");
    const count = () => document.querySelectorAll("ul.grid button.card").length;
    sc.scrollTop = 0;
    const deltas = [];
    const t0 = performance.now();
    let last = await new Promise(requestAnimationFrame);
    let settled = null;
    let cards = count();
    while (performance.now() - t0 < 180000) {
      sc.scrollTop += 120;
      const now = await new Promise(requestAnimationFrame);
      const atEnd = sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 2;
      if (!atEnd) deltas.push(now - last);
      last = now;
      if (count() !== cards || !atEnd) {
        cards = count();
        settled = null;
      } else if (settled === null) {
        settled = now;
      } else if (now - settled > 3000) {
        break;
      }
    }
    const ms = Math.round(performance.now() - t0 - 3000);
    await new Promise((r) => setTimeout(r, 2000));
    return { cards, ms, deltas, height: sc.scrollHeight };
  });

const showScroll = (s) => {
  const long = s.deltas.filter((d) => d > 50).length;
  return `${s.cards} cartes, ${s.height} px en ${s.ms} ms, ${s.deltas.length} images : médiane ${median(s.deltas).toFixed(1)} ms, p95 ${percentile(s.deltas, 0.95).toFixed(1)} ms, max ${Math.max(...s.deltas).toFixed(0)} ms, ${long} au-delà de 50 ms`;
};

/** Time until no image is being written: the cache's size holds still for 3 s. */
async function cacheSettled(page, timeoutMs = 120000) {
  const t0 = Date.now();
  let entries = -1;
  let since = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const now = (await invoke(page, "artwork_cache_stats")).entries;
    if (now !== entries) {
      entries = now;
      since = Date.now();
    } else if (Date.now() - since >= 3000) {
      return since - t0;
    }
    await page.waitForTimeout(250);
  }
  throw new Error(`cache still growing after ${timeoutMs} ms`);
}

const hitRate = (s) =>
  `${s.hits} trouvées, ${s.misses} téléchargées (${Math.round((100 * s.hits) / Math.max(1, s.hits + s.misses))} %), ${s.entries} fichiers, ${Math.round(s.bytes / (1024 * 1024))} Mio`;

/**
 * Runs `action` in the page, then polls the engine until the position advances:
 * a first position inside `from`, then 0.1 s more.
 */
const untilAdvancing = (page, click, seek, timeoutMs = 45000) =>
  page.evaluate(
    async ({ click, seek, timeoutMs }) => {
      const badge = () =>
        document
          .querySelector('[title="Décision du serveur Emby"]')
          ?.textContent?.trim() ?? null;
      const t0 = performance.now();
      if (click) document.querySelector(click).click();
      if (seek !== null)
        window.__viewer.invoke("player_command", {
          command: { type: "seekAbsolute", seconds: seek },
        });
      let badgeMs = null;
      let first = null;
      let last = null;
      while (performance.now() - t0 < timeoutMs) {
        if (badgeMs === null && badge())
          badgeMs = Math.round(performance.now() - t0);
        last = await window.__viewer
          .invoke("player_diagnostics")
          .catch(() => null);
        const pos = last?.timePos ?? null;
        const near =
          seek === null || (pos !== null && Math.abs(pos - seek) < 30);
        if (pos !== null && near) {
          if (first === null) first = pos;
          else if (pos >= first + 0.1)
            return {
              ok: true,
              ms: Math.round(performance.now() - t0),
              badgeMs,
              badge: badge(),
              last,
            };
        }
        await new Promise((r) => setTimeout(r, 40));
      }
      return { ok: false, ms: null, badgeMs, badge: badge(), last };
    },
    { click, seek, timeoutMs },
  );

const startFromDetail = (page) =>
  untilAdvancing(page, ".detail button.play", null);

/** Escape from the player: time until the detail page shows and the engine is closed. */
async function stopPlayback(page) {
  const t0 = Date.now();
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 15000 });
  const shown = Date.now() - t0;
  const closed = !(await diagnostics(page)).ok;
  return { shown, closed };
}

/** Sign-in if needed, then the home page; returns the launch's page. */
async function open(page) {
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await homeHeading(page).waitFor({ timeout: 20000 });
  await page.locator(".home[aria-busy=false]").waitFor({ timeout: 20000 });
}

const continueWatching = async (page) =>
  (await invoke(page, "emby_continue_watching"))
    .map((i) => i.id)
    .sort()
    .join(",");

/** Plays `SESSION_MS`, seeks, goes back to 0 and stops, on the open detail page. */
async function longSession(page, app, label) {
  const start = await startFromDetail(page);
  if (!start.ok) throw new Error(`${label}: no playback (${start.badge})`);
  const d = start.last;
  check(
    `${label}_start`,
    true,
    `${start.ms} ms, badge à ${start.badgeMs} ms : ${start.badge} · ${d.videoCodec} ${d.videoWidth}×${d.videoHeight} · ${d.audioCodec} · hwdec ${d.hwdecCurrent} · ${d.container} · ${d.videoOutput ?? "?"} ${d.gpuApi ?? ""}`,
  );
  check(`${label}_memory_at_start`, true, showMemory(memory(app.proc.pid)));
  const before = await diagnostics(page);
  await page.waitForTimeout(SESSION_MS);
  const after = await diagnostics(page);
  const played = after.timePos - before.timePos;
  check(
    `${label}_dropped_frames`,
    after.ok,
    `${played.toFixed(1)} s lues en ${SESSION_MS / 1000} s ; sortie vidéo ${after.frameDropCount - before.frameDropCount}, décodeur ${after.decoderFrameDropCount - before.decoderFrameDropCount} images perdues`,
  );
  check(`${label}_memory_after_60s`, true, showMemory(memory(app.proc.pid)));

  const seeks = [];
  for (const fraction of SEEK_FRACTIONS) {
    const target = Math.round(after.duration * fraction);
    const r = await untilAdvancing(page, null, target);
    if (r.ok) seeks.push(r.ms);
    else check(`${label}_seek_${target}_failed`, true, r.last?.timePos);
    await page.waitForTimeout(1000);
  }
  if (seeks.length) check(`${label}_seeks`, true, list(seeks));

  await page.evaluate(() =>
    window.__viewer.invoke("player_command", {
      command: { type: "seekAbsolute", seconds: 0 },
    }),
  );
  await untilAdvancing(page, null, 0);
  const stop = await stopPlayback(page);
  check(`${label}_stop`, stop.closed, `fiche affichée en ${stop.shown} ms`);
}

const firstFilm = async (page) => {
  await libraryNav(page).waitFor({ timeout: 20000 });
  const films = await libraryButton(page, "Films");
  await timedClick(page, films, FILMS_READY);
  await cards(page).first().click();
  await page.locator(".detail button.play").waitFor({ timeout: 15000 });
};

let app = null;
try {
  // 1. Empty artwork cache.
  app = await launch({ env: { VIEWER_E2E_ARTWORK_DIR: cacheDir } });
  let page = app.page;
  await open(page);
  const watching = await continueWatching(page);
  check("memory_home_cold", true, showMemory(memory(app.proc.pid)));
  const films = await libraryButton(page, "Films");
  const series = await libraryButton(page, "Séries");
  check(
    "films_first_posters_cold",
    true,
    `${await timedClick(page, films, FILMS_READY)} ms pour ${POSTERS} affiches`,
  );
  check("scroll_cold", true, showScroll(await scrollGrid(page)));
  check("cache_cold_settled", true, `${await cacheSettled(page)} ms`);
  check("cache_cold", true, hitRate(await invoke(page, "artwork_cache_stats")));
  check("memory_grid_cold", true, showMemory(memory(app.proc.pid)));
  check("exit_code_cold", (await app.close()) === 0);
  app = null;

  // 2. Same artwork cache, new process.
  app = await launch({ env: { VIEWER_E2E_ARTWORK_DIR: cacheDir } });
  page = app.page;
  await open(page);
  check("memory_home_warm", true, showMemory(memory(app.proc.pid)));
  check(
    "films_first_posters_warm",
    true,
    `${await timedClick(page, films, FILMS_READY)} ms pour ${POSTERS} affiches`,
  );
  check("scroll_warm", true, showScroll(await scrollGrid(page)));
  check("cache_warm_settled", true, `${await cacheSettled(page)} ms`);
  check(
    "cache_warm",
    true,
    `taux de succès ${hitRate(await invoke(page, "artwork_cache_stats"))}`,
  );
  check("memory_grid_warm", true, showMemory(memory(app.proc.pid)));

  // Navigation, each move repeated.
  const toHome = [];
  const toFilms = [];
  const toDetail = [];
  const detailBack = [];
  const toSeries = [];
  const home = { selector: "nav button", index: 0 };
  home.index = await page.evaluate(() =>
    [...document.querySelectorAll("nav button")].findIndex(
      (b) => b.textContent.trim() === "Accueil",
    ),
  );
  for (let i = 0; i < NAV_RUNS; i += 1) {
    toHome.push(
      await timedClick(page, home, {
        present: ".home[aria-busy=false]",
        absent: "ul.grid",
      }),
    );
    toFilms.push(await timedClick(page, films, FILMS_READY));
    toDetail.push(
      await timedClick(
        page,
        { selector: "ul.grid button.card" },
        { present: ".detail button.play" },
      ),
    );
    const t0 = Date.now();
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 10000 });
    detailBack.push(Date.now() - t0);
  }
  await timedClick(page, series, { present: "ul.grid button.card" });
  await page.locator("ul.grid button.card").first().waitFor();
  for (let i = 0; i < NAV_RUNS; i += 1) {
    toSeries.push(
      await timedClick(
        page,
        { selector: "ul.grid button.card" },
        { present: "ol.list li.episode button.thumb" },
      ),
    );
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 10000 });
  }
  check("nav_to_home", true, list(toHome));
  check("nav_to_films", true, list(toFilms));
  check("nav_to_film_detail", true, list(toDetail));
  check("nav_detail_back", true, list(detailBack));
  check("nav_to_series_detail", true, list(toSeries));

  // Films spread across the grid: start, the server's decision, stop.
  await timedClick(page, films, FILMS_READY);
  await scrollGrid(page);
  const total = await cards(page).count();
  const memoryBefore = memory(app.proc.pid);
  check("memory_before_samples", true, showMemory(memoryBefore));
  const starts = [];
  const stops = [];
  const methods = new Map();
  for (let i = 0; i < FILM_SAMPLES; i += 1) {
    const index = Math.floor(((i + 0.5) * total) / FILM_SAMPLES);
    await cards(page).nth(index).click();
    const play = page.locator(".detail button.play");
    await play.waitFor({ timeout: 15000 });
    const word = (await play.textContent()).trim();
    const r = await startFromDetail(page);
    const d = r.last ?? {};
    const method = (r.badge ?? "aucun badge").split(" · ")[0];
    methods.set(method, (methods.get(method) ?? 0) + 1);
    if (r.ok) starts.push(r.ms);
    check(
      `film_${index}`,
      r.ok,
      `${word} : ${r.ok ? `${r.ms} ms` : "pas de lecture en 45 s"}, ${r.badge ?? "aucun badge"} · ${d.videoCodec} ${d.videoWidth}×${d.videoHeight} · ${d.audioCodec} · hwdec ${d.hwdecCurrent} · ${d.container}`,
    );
    const stop = await stopPlayback(page);
    stops.push(stop.shown);
    check(`film_${index}_closed`, stop.closed, `${stop.shown} ms`);
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 10000 });
  }
  check("start_all_films", starts.length > 0, list(starts));
  check("stop_all_films", true, list(stops));
  check("server_decisions", true, JSON.stringify([...methods]));
  check("memory_after_samples", true, showMemory(memory(app.proc.pid)));

  await page.locator("ul.grid button.card").first().click();
  await page.locator(".detail button.play").waitFor({ timeout: 15000 });
  await longSession(page, app, "direct");
  check("exit_code_warm", (await app.close()) === 0);
  app = null;

  // 3. Transcode: the debug build caps the bitrate, as in gate2-transcode.
  app = await launch({
    env: {
      VIEWER_E2E_ARTWORK_DIR: cacheDir,
      VIEWER_E2E_MAX_BITRATE: "3000000",
    },
  });
  page = app.page;
  await open(page);
  await firstFilm(page);
  await longSession(page, app, "transcode");
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 10000 });
  check(
    "continue_watching_unchanged",
    (await continueWatching(page)) === watching,
  );
  await signOut(page);
  check("exit_code_transcode", (await app.close()) === 0);
  app = null;
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
} finally {
  if (app) await app.close().catch(() => {});
  rmSync(cacheDir, { recursive: true, force: true });
  finish();
}
