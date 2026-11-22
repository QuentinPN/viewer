// Gate 4 network interruptions during playback, on the real Emby test account,
// driven over CDP. The app signs in through the local proxy (net-proxy.mjs); only
// the app's own connections to the server are cut, never the PC's network, and the
// server is not touched.
// - a short outage (8 s) while a film plays: the playback must go on, without
//   "Lecture interrompue", well past what mpv had buffered;
// Writes then clears one resume position on the test account. Starts in any state,
// ends signed out with the direct server address as last server.
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
const { check, shot, finish } = createReport("gate4-network");
const proxy = await startProxy(account.url);
const BLIP_MS = 8000;
// Past mpv's read-ahead (~48 s seen in gate21-session) plus a margin.
const WATCH_S = 90;

const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__viewer.invoke("player_command", { command: c }),
    cmd,
  );
const itemDetail = (page, itemId) =>
  page.evaluate(
    (id) => window.__viewer.invoke("emby_item_detail", { itemId: id }),
    itemId,
  );

async function openLibrary(page, kind) {
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf(kind)).click();
  await cards(page).first().waitFor({ timeout: 20000 });
}

/**
 * Watches the playback for `seconds`: the furthest position reached, and whether
 * the player closed or the app said the playback was interrupted.
 */
async function watch(page, seconds) {
  const deadline = Date.now() + seconds * 1000;
  let furthest = 0;
  let buffering = false;
  while (Date.now() < deadline) {
    if (await page.getByText("Lecture interrompue.").count()) {
      return { interrupted: true, furthest, buffering };
    }
    if ((await playerBadge(page).count()) === 0) {
      return { closed: true, furthest, buffering };
    }
    if (await page.locator(".buffering").count()) buffering = true;
    const d = await diagnostics(page);
    if (d.ok) furthest = Math.max(furthest, d.timePos ?? 0);
    await page.waitForTimeout(500);
  }
  return { interrupted: false, closed: false, furthest, buffering };
}

let app = null;
let itemId = null;
try {
  // 0. Clean start, then sign-in through the proxy.
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

  // 1. Short outage while a film plays.
  await openLibrary(page, "Films");
  itemId = await cards(page).first().getAttribute("data-item-id");
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  const fromStart = page.locator(".detail button.from-start");
  await ((await fromStart.count()) ? fromStart : play).click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 5, 30000);
  const cutAt = started?.timePos ?? 0;
  await proxy.set("down");
  await page.waitForTimeout(BLIP_MS);
  await proxy.set("up");
  const seen = await watch(page, WATCH_S);
  check(
    "short_outage_playback_goes_on",
    !seen.interrupted && !seen.closed && seen.furthest >= cutAt + WATCH_S - 10,
    `coupure de ${BLIP_MS / 1000} s à ${cutAt.toFixed(1)} s ; ${seen.interrupted ? "« Lecture interrompue »" : seen.closed ? "lecteur fermé" : "toujours en lecture"} ; position atteinte ${seen.furthest.toFixed(1)} s${seen.buffering ? " ; chargement affiché" : ""}`,
  );
  await shot(page, "after-short-outage");

  // Cleanup: back to 0 so the stop report clears the resume point.
  if ((await playerBadge(page).count()) > 0) {
    await command(page, { type: "seekAbsolute", seconds: 0 });
    await waitForDiagnostics(
      page,
      (x) => (x.timePos ?? 99) < 3 && (x.timePos ?? 0) > 0,
    );
    await page.keyboard.press("Escape");
    await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });
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
      // Close the player or the banner, whatever is left.
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
            .click()
            .catch(() => {});
          await page.locator(".detail button.from-start").click();
          await playerBadge(page).waitFor({ timeout: 20000 });
          await waitForPosition(page, 1, 30000);
          await command(page, { type: "seekAbsolute", seconds: 0 });
          await waitForDiagnostics(
            page,
            (x) => (x.timePos ?? 99) < 3 && (x.timePos ?? 0) > 0,
          );
          await page.keyboard.press("Escape");
          await playerBadge(page).waitFor({
            state: "detached",
            timeout: 15000,
          });
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
