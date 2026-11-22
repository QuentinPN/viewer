// Gate 4 network cuts during an HLS transcode, on the real Emby test account, driven
// over CDP. The debug build caps the bitrate (VIEWER_E2E_MAX_BITRATE) so the
// server transcodes; the app goes through the local proxy (net-proxy.mjs), the PC's
// network and the server are not touched.
// - a short cut (8 s): the playback goes on past what mpv had read ahead, and no part
//   of the film is skipped (a lost HLS segment would show as a jump in the position);
// - a long cut: "Lecture interrompue", no false "watched", the position played reaches
//   the server once it is back, and "Reprendre" starts a new transcode there.
// Short transcodes; writes then clears one resume position. Starts in any state, ends
// signed out with the direct server address as last server.
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
const { check, shot, finish } = createReport("gate4-hls-cut");
const proxy = await startProxy(account.url);
const BLIP_MS = 8000;
const WATCH_S = 90;
// Step between two samples 0.5 s apart: above a slow sample, below one 3 s segment.
const JUMP_S = 2.5;
const RESUME_AT = 600;
// mpv's 150 MiB cache at the capped 3 Mbit/s is ~400 s, plus the 30 s stall limit.
const LONG_CUT_LIMIT_MS = 480000;
// How long the cut lasts once announced; HOLD_S tests a late stop on the server.
const HOLD_MS = Number(process.env.HOLD_S ?? 60) * 1000;
const RESEND_LIMIT_MS = 45000;
// The saved position is what was played, not the last progress before the cut.
const SAVED_MARGIN_S = 5;

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

/** The furthest position over `seconds`, the largest step forward, and how it ended. */
async function watch(page, seconds) {
  const deadline = Date.now() + seconds * 1000;
  let furthest = 0;
  let last = null;
  let jump = 0;
  let buffering = false;
  while (Date.now() < deadline) {
    if (await page.getByText("Lecture interrompue.").count())
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
  return { interrupted: false, closed: false, furthest, jump, buffering };
}

/** Back to 0 then Escape: the stop report leaves no resume point. */
async function rewindAndStop(page) {
  await command(page, { type: "seekAbsolute", seconds: 0 });
  await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    30000,
  );
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
}

let app = null;
let itemId = null;
try {
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
    await page.locator("#signin-title").waitFor({ timeout: 10000 });
  } else if (await libraryNav(page).count()) {
    await signOut(page);
  }
  await signIn(page, { url: proxy.url, user: account.user }, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });
  check("signed_in_through_proxy", true, proxy.url);

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

  // 1. Short cut during the transcode.
  await ((await fromStart.count()) ? fromStart : play).click();
  await playerBadge(page).waitFor({ timeout: 30000 });
  const badge = (await playerBadge(page).textContent()).trim();
  check("server_transcodes", badge.startsWith("Transcodage"), badge);
  const started = await waitForPosition(page, 5, 45000);
  check(
    "hls_stream",
    started?.ok && started.container === "hls",
    started?.container,
  );
  const cutAt = started?.timePos ?? 0;
  await proxy.set("down");
  await page.waitForTimeout(BLIP_MS);
  await proxy.set("up");
  const seen = await watch(page, WATCH_S);
  const state = seen.interrupted
    ? "« Lecture interrompue »"
    : seen.closed
      ? "lecteur fermé"
      : "toujours en lecture";
  check(
    "short_cut_playback_goes_on",
    !seen.interrupted && !seen.closed && seen.furthest >= cutAt + WATCH_S - 10,
    `coupure de ${BLIP_MS / 1000} s à ${cutAt.toFixed(1)} s ; ${state} ; position atteinte ${seen.furthest.toFixed(1)} s${seen.buffering ? " ; chargement affiché" : ""}`,
  );
  check(
    "short_cut_nothing_skipped",
    seen.jump < JUMP_S,
    `plus grand pas ${seen.jump.toFixed(1)} s`,
  );
  await shot(page, "after-short-cut");

  // 2. Long cut during the transcode, far enough into the film for the server to
  // keep a resume point.
  if ((await playerBadge(page).count()) > 0) {
    await command(page, { type: "seekAbsolute", seconds: RESUME_AT });
    const before = await waitForPosition(page, RESUME_AT + 15, 45000);
    await proxy.set("down");
    const longCutAt = Date.now();
    const banner = page.getByText("Lecture interrompue.");
    // What mpv read ahead can last minutes: the transcode runs faster than real time.
    const trace = [];
    let lastPos = before.timePos;
    let frozenAt = null;
    while (
      (await banner.count()) === 0 &&
      Date.now() - longCutAt < LONG_CUT_LIMIT_MS
    ) {
      const d = await diagnostics(page);
      if (d.ok && d.timePos !== lastPos) {
        lastPos = d.timePos;
        frozenAt = null;
      } else if (frozenAt === null) {
        frozenAt = Date.now();
      }
      const elapsed = Math.floor((Date.now() - longCutAt) / 1000);
      if (
        elapsed % 30 === 0 &&
        trace.at(-1)?.startsWith(`${elapsed} s`) !== true
      )
        trace.push(`${elapsed} s : ${d.timePos?.toFixed?.(1)}`);
      await page.waitForTimeout(500);
    }
    const announced = Date.now() - longCutAt;
    check(
      "long_cut_interrupted",
      (await banner.count()) > 0,
      `${(announced / 1000).toFixed(1)} s après la coupure à ${before.timePos?.toFixed(1)} s ; dernière position ${lastPos?.toFixed?.(1)} s ; ${frozenAt === null ? "pas d'arrêt de la position" : `position figée ${((Date.now() - frozenAt) / 1000).toFixed(1)} s`} ; ${trace.join(", ")}`,
    );
    await page.locator("#lost-title").waitFor({ timeout: 25000 });
    await shot(page, "long-cut");
    // The cut lasts on: the stop report waits in memory.
    await page.waitForTimeout(HOLD_MS);
    await proxy.set("up");
    await page.locator("#lost-title").waitFor({
      state: "detached",
      timeout: 40000,
    });
    // The lost stop report comes back within its 15 s cycle, no UI action needed.
    const backAt = Date.now();
    let after = await itemDetail(page, itemId);
    while (
      Math.abs((after.resume?.positionSeconds ?? 0) - lastPos) >
        SAVED_MARGIN_S &&
      Date.now() - backAt < RESEND_LIMIT_MS
    ) {
      await page.waitForTimeout(1000);
      after = await itemDetail(page, itemId);
    }
    const resentIn = Date.now() - backAt;
    check("long_cut_not_watched", after.played === false);
    const saved = after.resume?.positionSeconds ?? null;
    check(
      "long_cut_position_saved",
      saved !== null && Math.abs(saved - lastPos) <= SAVED_MARGIN_S,
      `serveur ${saved?.toFixed?.(1)} s, coupure à ${before.timePos?.toFixed(1)} s, lu jusqu'à ${lastPos?.toFixed?.(1)} s, reçu ${(resentIn / 1000).toFixed(1)} s après le retour (coupure prolongée de ${HOLD_MS / 1000} s)`,
    );

    // 3. "Reprendre": a new transcode at the saved position.
    await page
      .locator(".banner")
      .getByRole("button", { name: "Reprendre" })
      .click();
    await playerBadge(page).waitFor({ timeout: 60000 });
    const newBadge = (await playerBadge(page).textContent()).trim();
    const resumed = await waitForPosition(page, (saved ?? 0) - 15, 45000);
    check(
      "resume_transcodes_at_the_position",
      newBadge.startsWith("Transcodage") &&
        resumed?.ok &&
        resumed.timePos < (saved ?? 0) + 30,
      resumed?.ok
        ? `${newBadge} · ${resumed.timePos.toFixed(1)} s`
        : resumed?.error,
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
      await proxy.set("up");
      await page
        .locator("#lost-title")
        .waitFor({ state: "detached", timeout: 40000 })
        .catch(() => {});
      if ((await playerBadge(page).count()) > 0) {
        await page.keyboard.press("Escape");
        await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
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
  await proxy.stop();
  finish();
}
