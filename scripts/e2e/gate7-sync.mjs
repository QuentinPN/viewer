// Gate 7 linked playback between this PC and a real Android TV,
// driven over CDP on the desktop and adb on the TV:
// - the desktop plays the first episode of the first series and invites the TV;
// - the TV opens the same episode and the link is reported « Lié »;
// - the drift the TV measures (sent in its pings, shown in the desktop chip) is
//   sampled for STEADY_S seconds, then after a desktop pause/resume and a +10 s;
// - TV → desktop: play/pause and ±10 s from the remote (adb key events) reach the
//   desktop player;
// - leaving the player on the TV stops the desktop.
// Needs: the desktop debug build already signed in (this scenario never types a
// password), the TV debug app signed in and in the foreground, VIEWER_ADB_SERIAL
// (`adb devices` name of the TV). Server side effects: the episode is rewound to 0
// before the stop, so no resume point is kept.
import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  diagnostics,
  libraryNav,
  playerBadge,
  startScreen,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const STEADY_S = 60;
const serial = process.env.VIEWER_ADB_SERIAL;
if (!serial) throw new Error("VIEWER_ADB_SERIAL is required");

const { check, shot, finish, out: dir } = createReport("gate7-sync");

const call = async (page, cmd, args) => {
  const r = await page.evaluate(
    ([c, a]) =>
      window.__viewer
        .invoke(c, a)
        .then((value) => ({ value }))
        .catch((e) => ({ error: JSON.stringify(e) })),
    [cmd, args],
  );
  if (r.error) throw new Error(`${cmd} : ${r.error}`);
  return r.value;
};
const status = (page) => call(page, "sync_status");
const adb = (...args) =>
  spawnSync("adb", ["-s", serial, ...args], { encoding: "utf8" });
const tvKey = (key) => adb("shell", "input", "keyevent", key);
const tvShot = (name) => {
  const remote = `/sdcard/viewer-${name}.png`;
  adb("shell", "screencap", "-p", remote);
  adb("pull", remote, join(dir, `tv-${name}.png`));
  adb("shell", "rm", remote);
};

const stats = (values) => {
  if (values.length === 0) return null;
  const abs = values.map(Math.abs).sort((a, b) => a - b);
  const at = (q) => abs[Math.min(abs.length - 1, Math.floor(q * abs.length))];
  return { n: abs.length, median: at(0.5), p95: at(0.95), max: abs.at(-1) };
};
const fmt = (s) =>
  s ? `n=${s.n} médiane ${s.median} ms, p95 ${s.p95} ms, max ${s.max} ms` : "—";

/** Drift values reported while linked, sampled every 500 ms for `seconds`. */
async function sampleDrift(page, seconds) {
  const values = [];
  const series = [];
  const begin = Date.now();
  const deadline = begin + seconds * 1000;
  let unlinked = 0;
  while (Date.now() < deadline) {
    const s = await status(page);
    if (s.phase !== "linked") unlinked += 1;
    else if (s.driftMs != null) {
      values.push(s.driftMs);
      series.push([Date.now() - begin, s.driftMs]);
    }
    await page.waitForTimeout(500);
  }
  return { values, series, unlinked };
}

/** Milliseconds until |drift| ≤ limit for 3 samples in a row, or null. */
async function settleTime(page, limit, timeoutMs) {
  const start = Date.now();
  let streak = 0;
  // The drift of before the jump may still be shown for one ping.
  await page.waitForTimeout(1200);
  while (Date.now() - start < timeoutMs) {
    const s = await status(page);
    if (
      s.phase === "linked" &&
      s.driftMs != null &&
      Math.abs(s.driftMs) <= limit
    )
      streak += 1;
    else streak = 0;
    if (streak >= 3) return Date.now() - start;
    await page.waitForTimeout(250);
  }
  return null;
}

/** Ms until the desktop player matches `accept`, polled every 50 ms. */
async function reaction(page, accept, timeoutMs = 5000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const d = await diagnostics(page);
    if (d.ok && accept(d)) return { ms: Date.now() - start, d };
    await page.waitForTimeout(50);
  }
  return null;
}

let app = await launch();
try {
  const page = app.page;
  const screen = await startScreen(page);
  if (screen !== "signedIn")
    throw new Error(
      "le build debug doit déjà être connecté (aucun mot de passe tapé ici)",
    );
  await libraryNav(page).waitFor({ timeout: 20000 });

  // 1. The first episode of the first series, played on the desktop.
  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Séries")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  const episodeButton = page.locator("li.episode button.thumb").first();
  await episodeButton.waitFor({ timeout: 20000 });
  await episodeButton.click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 0.5, 20000);
  check(
    "desktop_playing",
    !!started,
    (await playerBadge(page).textContent())?.trim(),
  );

  // 2. Invite the TV.
  const devices = await call(page, "sync_devices");
  check("tv_listed", devices.length > 0, devices.map((d) => d.name).join(", "));
  if (devices.length === 0) throw new Error("aucune TV en ligne");
  const invitedAt = Date.now();
  await call(page, "sync_invite", { sessionId: devices[0].sessionId });
  let s = await status(page);
  while (s.phase !== "linked" && Date.now() - invitedAt < 45000) {
    await page.waitForTimeout(250);
    s = await status(page);
  }
  check(
    "linked",
    s.phase === "linked",
    `${s.device ?? "?"} en ${Date.now() - invitedAt} ms`,
  );
  if (s.phase !== "linked") throw new Error("liaison non établie");
  await shot(page, "linked");
  tvShot("linked");

  // 3. Steady drift.
  await page.waitForTimeout(3000);
  const steady = await sampleDrift(page, STEADY_S);
  const steadyStats = stats(steady.values);
  writeFileSync(join(dir, "steady-drift.json"), JSON.stringify(steady.series));
  check(
    "steady_drift_p95_under_150",
    steadyStats && steadyStats.p95 <= 150 && steady.unlinked === 0,
    `${fmt(steadyStats)} ; échantillons hors liaison ${steady.unlinked}`,
  );

  // 4. Desktop pause / resume / +10 s, then the drift settles again.
  await page.keyboard.press("Space");
  await page.waitForTimeout(2500);
  tvShot("desktop-paused");
  check("still_linked_paused", (await status(page)).phase === "linked");
  await page.keyboard.press("Space");
  const afterResume = await settleTime(page, 60, 15000);
  check("drift_settles_after_resume", afterResume != null, `${afterResume} ms`);
  await page.keyboard.press("ArrowRight");
  const afterSeek = await settleTime(page, 60, 15000);
  check("drift_settles_after_seek", afterSeek != null, `${afterSeek} ms`);

  // 5. TV → desktop.
  tvKey("KEYCODE_MEDIA_PLAY_PAUSE");
  const paused = await reaction(page, (d) => d.paused === true);
  check(
    "tv_pause_reaches_desktop",
    !!paused,
    `${paused?.ms} ms (après retour d'adb)`,
  );
  tvKey("KEYCODE_MEDIA_PLAY_PAUSE");
  const resumed = await reaction(page, (d) => d.paused === false);
  check(
    "tv_play_reaches_desktop",
    !!resumed,
    `${resumed?.ms} ms (après retour d'adb)`,
  );
  await page.waitForTimeout(2000);
  const before = (await diagnostics(page)).timePos;
  tvKey("KEYCODE_DPAD_RIGHT");
  const fwd = await reaction(page, (d) => d.timePos >= before + 8, 8000);
  check(
    "tv_forward_reaches_desktop",
    !!fwd,
    `${before?.toFixed(1)} → ${fwd?.d.timePos?.toFixed(1)} s en ${fwd?.ms} ms après retour d'adb`,
  );
  await page.waitForTimeout(2000);
  const before2 = (await diagnostics(page)).timePos;
  tvKey("KEYCODE_DPAD_LEFT");
  const back = await reaction(page, (d) => d.timePos <= before2 - 8, 8000);
  check(
    "tv_back_reaches_desktop",
    !!back,
    `${before2?.toFixed(1)} → ${back?.d.timePos?.toFixed(1)} s en ${back?.ms} ms après retour d'adb`,
  );
  const afterTvSeek = await settleTime(page, 60, 15000);
  check(
    "drift_settles_after_tv_seek",
    afterTvSeek != null,
    `${afterTvSeek} ms`,
  );

  // 6. Rewind (no resume point), then leaving on the TV stops the desktop.
  await call(page, "player_command", {
    command: { type: "seekAbsolute", seconds: 0 },
  });
  await waitForDiagnostics(page, (d) => (d.timePos ?? 99) < 3);
  await page.waitForTimeout(3000);
  tvShot("rewound");
  tvKey("KEYCODE_BACK");
  let stopped = await playerBadge(page)
    .waitFor({ state: "detached", timeout: 6000 })
    .then(() => true)
    .catch(() => false);
  if (!stopped) {
    // The first Back may only hide the TV controls.
    tvKey("KEYCODE_BACK");
    stopped = await playerBadge(page)
      .waitFor({ state: "detached", timeout: 6000 })
      .then(() => true)
      .catch(() => false);
  }
  check("tv_exit_stops_desktop", stopped);
  check("unlinked_after_exit", (await status(page)).phase === "idle");
  tvShot("after-exit");

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
  finish();
}
