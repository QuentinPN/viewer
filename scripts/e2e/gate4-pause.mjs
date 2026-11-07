// Gate 4 long pause during an HLS transcode, on the real Emby test account, driven over
// CDP. The debug build caps the bitrate (VIEWER_E2E_MAX_BITRATE) so the server transcodes;
// the app goes through the local proxy (net-proxy.mjs), which only notes the segments.
// A second device watches /Sessions, so that polling is not activity of the player.
// - paused long enough for mpv's cache to fill: no error shows, the position does not
//   move, and the encoder's state and progress on the server are noted;
// - resumed: playback goes on past what mpv had read before the pause, with no jump;
// - paused again, then a seek outside the cache lands there.
// PAUSE_S sets the first pause. Writes then clears one resume position. Starts in any
// state, ends signed out with the direct server address as last server.
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  cards,
  createReport,
  diagnostics,
  libraryNav,
  playerBadge,
  signIn,
  signInHeading,
  signOut,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
if (!account.url || /^http:\/\/(127\.0\.0\.1|localhost)/.test(account.url))
  throw new Error(
    `VIEWER_EMBY_URL must be the server address, got ${account.url}`,
  );
const { check, shot, finish } = createReport("gate4-pause");
const proxy = await startProxy(account.url);
const PAUSE_MS = Number(process.env.PAUSE_S ?? 300) * 1000;
const SECOND_PAUSE_MS = 60000;
const SAMPLE_MS = 300000;
const SEGMENT_S = 3;
// Step between two samples 0.5 s apart: above a slow sample, below one 3 s segment.
const JUMP_S = 2.5;
const PAST_BOUNDARY_S = 30;
const SEEK_FORWARD_S = 1200;

const OBSERVER_DEVICE = "viewer-e2e-observer";
const observerAuth = `MediaBrowser Client="Viewer E2E", Device="observer", DeviceId="${OBSERVER_DEVICE}", Version="0.1.0"`;

async function observerSignIn() {
  const res = await fetch(`${account.url}/emby/Users/AuthenticateByName`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Emby-Authorization": observerAuth,
    },
    body: JSON.stringify({ Username: account.user, Pw: account.password }),
  });
  if (!res.ok) throw new Error(`observer sign-in: HTTP ${res.status}`);
  return (await res.json()).AccessToken;
}

const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__TAURI_INTERNALS__.invoke("player_command", { command: c }),
    cmd,
  );
const itemDetail = (page, itemId) =>
  page.evaluate(
    (id) =>
      window.__TAURI_INTERNALS__.invoke("emby_item_detail", { itemId: id }),
    itemId,
  );
const interrupted = async (page) =>
  (await page.getByText("Lecture interrompue.").count()) > 0;

let app = null;
let itemId = null;
let token = null;

const transcoding = async () => {
  const res = await fetch(`${account.url}/emby/Sessions`, {
    headers: { "X-Emby-Token": token },
  });
  if (!res.ok) throw new Error(`observer sessions: HTTP ${res.status}`);
  const sessions = await res.json();
  return (
    sessions.find((s) => s.DeviceId !== OBSERVER_DEVICE && s.TranscodingInfo)
      ?.TranscodingInfo ?? null
  );
};

/** Waits while paused, noting each change of the encoder and its regular progress. */
async function holdPause(page, limitMs) {
  const began = Date.now();
  const startPos = (await diagnostics(page)).timePos;
  const trace = [];
  let last = null;
  let lastSample = 0;
  let moved = false;
  let failed = false;
  while (Date.now() - began < limitMs) {
    const info = await transcoding();
    const on = info !== null;
    const elapsed = Math.round((Date.now() - began) / 1000);
    if (on !== last || Date.now() - lastSample >= SAMPLE_MS) {
      trace.push(
        on
          ? `${elapsed} s : oui, ${info.CompletionPercentage?.toFixed(2)} %, processeur ${info.CurrentCpuUsage}`
          : `${elapsed} s : non`,
      );
      lastSample = Date.now();
    }
    last = on;
    if (await interrupted(page)) failed = true;
    const d = await diagnostics(page);
    if (d.ok && Math.abs(d.timePos - startPos) > 0.1) moved = true;
    await page.waitForTimeout(5000);
  }
  return {
    trace: trace.join(", "),
    encoderGone: last === false,
    moved,
    failed,
  };
}

/** Plays until `target`; returns the furthest position, largest step and how it ended. */
async function playUntil(page, target, limitMs) {
  const deadline = Date.now() + limitMs;
  let furthest = 0;
  let last = null;
  let jump = 0;
  let buffering = false;
  while (Date.now() < deadline && furthest < target) {
    if (await interrupted(page))
      return { interrupted: true, furthest, jump, buffering };
    if ((await playerBadge(page).count()) === 0)
      return { closed: true, furthest, jump, buffering };
    if (await page.locator(".buffering").count()) buffering = true;
    const d = await diagnostics(page);
    if (d.ok && d.timePos != null) {
      if (last !== null) jump = Math.max(jump, d.timePos - last);
      last = d.timePos;
      furthest = Math.max(furthest, d.timePos);
    }
    await page.waitForTimeout(500);
  }
  return { furthest, jump, buffering };
}

async function rewindAndStop(page) {
  await command(page, { type: "setPaused", paused: false });
  await command(page, { type: "seekAbsolute", seconds: 0 });
  await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    45000,
  );
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
}

try {
  token = await observerSignIn();
  await proxy.set("log-segments");
  app = await launch({ env: { VIEWER_E2E_MAX_BITRATE: "3000000" } });
  const page = app.page;
  await Promise.race([
    signInHeading(page).waitFor({ timeout: 40000 }),
    libraryNav(page).waitFor({ timeout: 40000 }),
    page.locator("#lost-title").waitFor({ timeout: 40000 }),
  ]);
  if (await page.locator("#lost-title").count()) {
    // Left by an interrupted run (proxy gone).
    await page.getByRole("button", { name: "Changer de serveur" }).click();
    await page.locator("#server-url").waitFor({ timeout: 10000 });
  } else if (await libraryNav(page).count()) {
    await signOut(page);
  }
  await signIn(page, { url: proxy.url, user: account.user }, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });

  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  itemId = await cards(page).first().getAttribute("data-item-id");
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  const fromStart = page.locator(".detail button.from-start");
  await play.waitFor({ timeout: 15000 });
  await ((await fromStart.count()) ? fromStart : play).click();
  await playerBadge(page).waitFor({ timeout: 30000 });
  const badge = (await playerBadge(page).textContent()).trim();
  check("server_transcodes", badge.startsWith("Transcodage"), badge);
  await waitForPosition(page, 5, 45000);

  // 1. First pause.
  await command(page, { type: "setPaused", paused: true });
  const paused = await waitForDiagnostics(page, (d) => d.paused === true, 5000);
  check(
    "paused",
    paused?.paused === true,
    `à ${paused?.timePos?.toFixed(1)} s`,
  );
  const pause = await holdPause(page, PAUSE_MS);
  const lastBefore = proxy.segments.at(-1);
  const boundary = ((lastBefore?.number ?? 0) + 1) * SEGMENT_S;
  check("no_error_during_pause", !pause.failed);
  check("position_held", !pause.moved);
  check(
    "encoder_during_pause",
    true,
    `encodage : ${pause.trace} ; ${pause.encoderGone ? "absent" : "présent"} en fin de pause ; ${proxy.segments.length} segments demandés, dernier n° ${lastBefore?.number}, il y a ${((Date.now() - (lastBefore?.at ?? Date.now())) / 1000).toFixed(0)} s`,
  );
  await shot(page, "paused");

  // 2. Resume, then play past what mpv had before the pause.
  const countBefore = proxy.segments.length;
  const resumedAt = Date.now();
  await command(page, { type: "setPaused", paused: false });
  const from = (await diagnostics(page)).timePos ?? 0;
  const target = boundary + PAST_BOUNDARY_S;
  const played = await playUntil(page, target, (target - from + 120) * 1000);
  const after = proxy.segments.slice(countBefore);
  const firstAfter = after[0];
  const refused = after.filter((s) => s.status !== 200);
  check(
    "resume_goes_past_the_cache",
    !played.interrupted && !played.closed && played.furthest >= target,
    `de ${from.toFixed(1)} s à ${played.furthest.toFixed(1)} s, limite de la lecture d'avance ${boundary} s, en ${((Date.now() - resumedAt) / 1000).toFixed(0)} s${played.buffering ? " ; chargement affiché" : ""}`,
  );
  check(
    "resume_nothing_skipped",
    played.jump < JUMP_S,
    `plus grand pas ${played.jump.toFixed(1)} s`,
  );
  check(
    "next_segments_served",
    after.length > 0 && refused.length === 0,
    `${after.length} segments après la reprise, ${refused.length} refusés${
      refused.length
        ? ` (${refused
            .slice(0, 5)
            .map((s) => `n° ${s.number} : ${s.status}`)
            .join(", ")})`
        : ""
    } ; premier n° ${firstAfter?.number} ${firstAfter ? `${((firstAfter.at - resumedAt) / 1000).toFixed(0)} s après la reprise` : ""}`,
  );
  await shot(page, "resumed");

  // 3. Second pause, then a seek outside the cache.
  if ((await playerBadge(page).count()) > 0) {
    await command(page, { type: "setPaused", paused: true });
    const second = await holdPause(page, SECOND_PAUSE_MS);
    const at = (await diagnostics(page)).timePos ?? 0;
    check(
      "second_pause",
      !second.failed,
      `encodage : ${second.trace} ; ${second.encoderGone ? "absent" : "présent"} à la fin`,
    );
    const seekTo = at + SEEK_FORWARD_S;
    await command(page, { type: "setPaused", paused: false });
    await command(page, { type: "seekAbsolute", seconds: seekTo });
    const seekAt = Date.now();
    const there = await waitForPosition(page, seekTo + 1, 60000);
    check(
      "seek_after_pause",
      there?.ok && there.timePos >= seekTo && there.timePos < seekTo + 30,
      there?.ok
        ? `${there.timePos.toFixed(1)} s en ${((Date.now() - seekAt) / 1000).toFixed(1)} s, visé ${seekTo.toFixed(1)} s`
        : there?.error,
    );
    await rewindAndStop(page);
  }
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) {
    const page = app.page;
    try {
      if ((await playerBadge(page).count()) > 0) {
        await rewindAndStop(page).catch(async () => {
          await page.keyboard.press("Escape");
          await playerBadge(page).waitFor({
            state: "detached",
            timeout: 15000,
          });
        });
      }
      if (itemId) {
        let detail = await itemDetail(page, itemId);
        if (detail.resume !== null) {
          // An interrupted playback left a position: play from 0 and stop.
          await page
            .locator(".banner button.secondary")
            .click({ timeout: 3000 })
            .catch(() => {});
          await page.locator(".detail button.from-start").click();
          await playerBadge(page).waitFor({ timeout: 30000 });
          await waitForPosition(page, 1, 45000);
          await rewindAndStop(page);
          detail = await itemDetail(page, itemId);
        }
        check(
          "cleanup_resume_cleared",
          detail.resume === null && !detail.played,
        );
      }
      // Leave the direct server address as last server.
      await signOut(page);
      await signIn(
        page,
        { url: account.url, user: account.user },
        account.password,
      );
      await libraryNav(page).waitFor({ timeout: 20000 });
      await signOut(page);
      check("exit_code", (await app.close()) === 0);
      app = null;
    } catch (e) {
      check(
        "cleanup_error",
        false,
        e instanceof Error ? e.message.split("\n")[0] : e,
      );
    }
  }
  if (app) await app.close().catch(() => {});
  if (token)
    await fetch(`${account.url}/emby/Sessions/Logout`, {
      method: "POST",
      headers: { "X-Emby-Token": token },
    }).catch(() => {});
  await proxy.stop();
  finish();
}
