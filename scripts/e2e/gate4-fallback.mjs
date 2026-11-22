// Gate 4 fallback when the player cannot open the direct source, on the
// real Emby test account, driven over CDP. The app signs in through the local proxy
// (net-proxy.mjs), which answers direct file requests with bytes no player can read;
// the file on the NAS is untouched.
// - "Reprendre": the server transcodes and playback starts at the saved position;
// - "Lire depuis le début": the fallback keeps that choice;
// - a transcode that cannot open either: an explanation, "Retour", no retry loop;
// - a direct source that never answers: the opening limit, then the
//   fallback;
// - a file the server can no longer read (HTTP 500 on the file and on every HLS
//   segment): the fallback, then the opening limit and the explanation.
// Short sessions; writes then clears one resume position. Starts in any state, ends
// signed out with the direct server address as last server.
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  cards,
  createReport,
  detailTitle,
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
const { check, shot, finish } = createReport("gate4-fallback");
const proxy = await startProxy(account.url);
const RESUME_AT = 600;
const FALLBACK_BADGE = "Transcodage · échec de la lecture directe";
const OPEN_LIMIT_MS = 45000; // OPEN_LIMIT in src-tauri/src/player/mod.rs

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
  app = await launch();
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

  // 1. A resume point, through a working direct playback.
  await ((await fromStart.count()) ? fromStart : play).click();
  await playerBadge(page).waitFor({ timeout: 30000 });
  const direct = (await playerBadge(page).textContent()).trim();
  check(
    "direct_play_when_the_file_opens",
    direct.startsWith("Lecture directe"),
    direct,
  );
  await waitForPosition(page, 1, 30000);
  await command(page, { type: "seekAbsolute", seconds: RESUME_AT });
  await waitForPosition(page, RESUME_AT + 1, 30000);
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Reprendre" }).waitFor({ timeout: 15000 });
  check(
    "resume_point_written",
    true,
    (await detailTitle(page).textContent()).trim(),
  );

  // 2. "Reprendre" on a file the player cannot open.
  await proxy.set("junk-direct");
  await play.click();
  await playerBadge(page).waitFor({ timeout: 60000 });
  const badge = (await playerBadge(page).textContent()).trim();
  check("fallback_transcodes", badge === FALLBACK_BADGE, badge);
  check(
    "direct_source_was_refused",
    proxy.counts.junk > 0,
    `${proxy.counts.junk} réponses illisibles`,
  );
  const resumed = await waitForPosition(page, RESUME_AT - 15, 45000);
  check(
    "fallback_keeps_the_resume_point",
    resumed?.ok && resumed.timePos < RESUME_AT + 60,
    resumed?.ok
      ? `${resumed.timePos.toFixed(1)} s · ${resumed.videoCodec} · ${resumed.container}`
      : resumed?.error,
  );
  await shot(page, "fallback-playing");
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Reprendre" }).waitFor({ timeout: 15000 });
  check("resume_point_kept_after_stop", true);

  // 3. "Lire depuis le début" on the same file: the choice survives the fallback.
  await fromStart.click();
  await playerBadge(page).waitFor({ timeout: 60000 });
  const fromZero = await waitForPosition(page, 0.5, 45000);
  check(
    "fallback_keeps_from_start",
    fromZero?.ok && fromZero.timePos < 60,
    fromZero?.ok ? `${fromZero.timePos.toFixed(1)} s` : fromZero?.error,
  );
  await rewindAndStop(page);
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });

  // 4. The transcode cannot open either: an explanation and a way back, no loop.
  await proxy.set("junk-all");
  await play.focus();
  await page.keyboard.press("Enter");
  const failure = page.locator(".opening-panel .error");
  await failure.waitFor({ timeout: 60000 });
  const message = (await failure.textContent()).trim();
  check(
    "double_failure_is_explained",
    message.startsWith("Le lecteur n'a pas pu ouvrir ce fichier."),
    message,
  );
  const before = proxy.counts.junk;
  await page.waitForTimeout(5000);
  check(
    "no_retry_loop",
    proxy.counts.junk === before && (await failure.count()) === 1,
    `${before} → ${proxy.counts.junk} réponses illisibles en 5 s`,
  );
  await shot(page, "double-failure");
  await page.getByRole("button", { name: "Retour" }).click();
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  const after = await diagnostics(page);
  check("player_closed", !after.ok, after.error ?? "still open");
  check(
    "focus_back_on_play",
    await play.evaluate((el) => el === document.activeElement),
  );

  // 5. A direct source that never answers: the limit, then the fallback.
  await proxy.set("hold-direct");
  let opened = Date.now();
  await play.focus();
  await page.keyboard.press("Enter");
  await page.locator(".opening-panel").waitFor({ timeout: 10000 });
  await page.waitForTimeout(3000);
  const waiting = (await page.locator(".opening-panel").textContent()).trim();
  check(
    "silent_source_shows_the_opening",
    waiting.includes("Ouverture du flux") &&
      (await playerBadge(page).count()) === 0,
    waiting,
  );
  await playerBadge(page).waitFor({ timeout: OPEN_LIMIT_MS + 30000 });
  const heldFor = Date.now() - opened;
  const heldBadge = (await playerBadge(page).textContent()).trim();
  check(
    "silent_source_falls_back_at_the_limit",
    heldBadge === FALLBACK_BADGE &&
      proxy.counts.held > 0 &&
      heldFor >= OPEN_LIMIT_MS - 1000,
    `${heldBadge} après ${heldFor} ms, ${proxy.counts.held} requête(s) sans réponse`,
  );
  const afterLimit = await waitForPosition(page, 0.5, 45000);
  check(
    "fallback_plays_after_the_limit",
    (afterLimit?.timePos ?? 0) > 0.5,
    `${afterLimit?.timePos} s`,
  );
  await rewindAndStop(page);
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });

  // 6. The server can no longer read the file: fallback, limit, explanation.
  await proxy.set("error-files");
  opened = Date.now();
  await play.focus();
  await page.keyboard.press("Enter");
  await failure.waitFor({ timeout: OPEN_LIMIT_MS + 30000 });
  const failedAfter = Date.now() - opened;
  const failureText = (await failure.textContent()).trim();
  check(
    "unreadable_file_is_explained_at_the_limit",
    failureText ===
      "Le lecteur n'a pas pu ouvrir ce fichier. Détail technique : le flux ne s'ouvre pas." &&
      failedAfter >= OPEN_LIMIT_MS - 1000,
    `${failureText} (${failedAfter} ms)`,
  );
  const errorsBefore = proxy.counts.errors;
  await page.waitForTimeout(2000);
  const settled = proxy.counts.errors;
  await page.waitForTimeout(3000);
  check(
    "requests_stop_after_the_limit",
    errorsBefore > 1 && proxy.counts.errors === settled,
    `${errorsBefore} réponses 500, puis ${proxy.counts.errors}`,
  );
  await shot(page, "opening-limit");
  await page.getByRole("button", { name: "Retour" }).click();
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  check("player_closed_after_the_limit", !(await diagnostics(page)).ok);
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
      if ((await page.locator(".opening-panel").count()) > 0) {
        await page.getByRole("button", { name: "Retour" }).click();
        await page.locator(".opening-panel").waitFor({
          state: "detached",
          timeout: 15000,
        });
      }
      if ((await playerBadge(page).count()) > 0) {
        await page.keyboard.press("Escape");
        await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
      }
      if (itemId) {
        let detail = await itemDetail(page, itemId);
        if (detail.resume !== null) {
          await page.locator(".detail button.from-start").click();
          await playerBadge(page).waitFor({ timeout: 30000 });
          await waitForPosition(page, 1, 30000);
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
