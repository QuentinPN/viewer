// Gate 2.1 session and reconnection scenario (ADR 0012), driven over CDP. The app
// signs in through a local proxy (net-proxy.mjs) whose modes simulate the server
// switched off, hanging, or revoking the token; the server itself is never touched.
// - restart: the session comes back without the sign-in form;
// - Server off at startup: recovery screen, token kept, "Réessayer", automatic return;
// - Server hanging at startup: the app's timeout fires, token kept;
// - Server lost while browsing: overlay over the app, automatic return, detail page
//   reloaded and grid scroll kept;
// - token revoked (401 on every authenticated request): one reauthentication
//   screen, server and user prefilled, the navigation kept after the password;
// - "Changer de serveur", then the session tile of the shelf without a password;
// - stream cut during playback: "Lecture interrompue", no false "watched", resume
//   from the server position, then the position is cleared again.
// Starts in any state, ends signed out with the direct server address as last server.
import { spawnSync } from "node:child_process";
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  accountAction,
  cards,
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  playerBadge,
  signIn,
  signInHeading,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
if (!account.url || /^http:\/\/(127\.0\.0\.1|localhost)/.test(account.url))
  throw new Error(
    `VIEWER_EMBY_URL must be the server address, got ${account.url}`,
  );
const { check, shot, finish } = createReport("gate21-session");
const proxy = await startProxy(account.url);
console.log(`proxy ${proxy.url} → server`);

const lostTitle = (page) => page.locator("#lost-title");
const invoke = (page, cmd, args = {}) =>
  page.evaluate(
    ([c, a]) =>
      window.__TAURI_INTERNALS__
        .invoke(c, a)
        .then((value) => ({ ok: true, value }))
        .catch((error) => ({ ok: false, error })),
    [cmd, args],
  );
const text = async (locator) => (await locator.textContent())?.trim() ?? "";

/** Viewer tokens in the Windows Credential Manager (names only, no secret). */
function vaultEntries() {
  const r = spawnSync("cmdkey", ["/list"], { encoding: "utf8" });
  return r.stdout
    .split(/\r?\n/)
    .filter((l) => /Viewer/i.test(l) && /cible|target/i.test(l)).length;
}

/**
 * Waits for one of the first screens; records whether the sign-in form showed up
 * on the way (it must not while a session is being restored).
 */
async function firstScreen(page, timeout = 40000) {
  const deadline = Date.now() + timeout;
  let sawSignIn = false;
  while (Date.now() < deadline) {
    const s = await page.evaluate(() => ({
      signIn: !!document.querySelector("#signin-title"),
      lost: !!document.querySelector("#lost-title"),
      app: !!document.querySelector('nav[aria-label="Bibliothèques"]'),
      restoring: !!document.querySelector("main.screen[aria-busy='true']"),
      reauth:
        !!document.querySelector("#password") &&
        !document.querySelector("#server-url"),
    }));
    if (s.signIn && !s.reauth) sawSignIn = true;
    if (s.lost) return { screen: "lost", sawSignIn };
    if (s.app && !s.lost && !s.signIn) return { screen: "app", sawSignIn };
    if (s.signIn) return { screen: s.reauth ? "reauth" : "signIn", sawSignIn };
    await page.waitForTimeout(100);
  }
  return { screen: "timeout", sawSignIn };
}

/** The signed-in app is shown and usable (no overlay over it). */
const appUsable = (page) =>
  page.evaluate(() => {
    const nav = document.querySelector('nav[aria-label="Bibliothèques"]');
    return (
      !!nav && !nav.closest("[inert]") && !document.querySelector("#lost-title")
    );
  });

async function waitAppUsable(page, timeout) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await appUsable(page)) return Date.now() - start;
    await page.waitForTimeout(100);
  }
  return null;
}

async function openLibrary(page, kind) {
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf(kind)).click();
  await cards(page).first().waitFor({ timeout: 20000 });
}

let app = null;
try {
  // 0. Clean start, then sign-in through the proxy.
  app = await launch();
  let page = app.page;
  let first = await firstScreen(page);
  if (first.screen === "app") {
    await accountAction(page, "Se déconnecter");
    await signInHeading(page).waitFor({ timeout: 15000 });
  } else if (first.screen === "lost" || first.screen === "reauth") {
    // Left by an interrupted run (proxy gone): the same account signs in again
    // below, which replaces that session and its token.
    // "Retour" when no app is behind the password card (ADR 0103).
    await page
      .getByRole("button", { name: /^(Changer de serveur|Retour)$/ })
      .click();
    await signInHeading(page).waitFor({ timeout: 10000 });
  } else if (first.screen !== "signIn") {
    throw new Error(`unexpected first screen ${first.screen}`);
  }
  const leftover = first.screen === "lost" || first.screen === "reauth" ? 1 : 0;
  const vaultBefore = vaultEntries() - leftover;
  await signIn(page, { url: proxy.url, user: account.user }, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });
  const vaultSignedIn = vaultEntries();
  check(
    "signed_in_through_proxy",
    vaultSignedIn === vaultBefore + 1,
    `${vaultBefore} → ${vaultSignedIn} jeton(s)`,
  );
  check("exit_code_1", (await app.close()) === 0);

  // 1. Restart: restored without the form.
  app = await launch();
  page = app.page;
  first = await firstScreen(page);
  check("restart_restores_session", first.screen === "app", first.screen);
  check("restart_no_signin_form", !first.sawSignIn);
  await homeHeading(page).waitFor({ timeout: 20000 });
  await shot(page, "restored");
  check("exit_code_2", (await app.close()) === 0);

  // 2. Server off at startup.
  await proxy.set("down");
  app = await launch();
  page = app.page;
  first = await firstScreen(page);
  check("offline_start_recovery_screen", first.screen === "lost", first.screen);
  check("offline_start_no_signin_form", !first.sawSignIn);
  check("offline_start_token_kept", vaultEntries() === vaultSignedIn);
  await page
    .getByText(/Nouvelle tentative dans \d+ s/)
    .waitFor({ timeout: 10000 });
  check(
    "offline_start_retry_focused",
    await page.evaluate(
      () => document.activeElement?.textContent?.trim() === "Réessayer",
    ),
  );
  check(
    "offline_start_names_server",
    /injoignable/i.test(await text(lostTitle(page))) &&
      (await page.locator(".server-meta").first().textContent())?.includes(
        proxy.url,
      ),
  );
  await shot(page, "offline-start");
  await page.getByRole("button", { name: "Réessayer" }).click();
  await page
    .getByText(/Nouvelle tentative dans \d+ s/)
    .waitFor({ timeout: 10000 });
  check("manual_retry_still_offline", (await lostTitle(page).count()) === 1);
  const upAt = Date.now();
  await proxy.set("up");
  const back = await waitAppUsable(page, 40000);
  check(
    "automatic_return",
    back !== null,
    `${Date.now() - upAt} ms après le retour du réseau`,
  );
  check("token_still_kept", vaultEntries() === vaultSignedIn);
  check("exit_code_3", (await app.close()) === 0);

  // 3. Server hanging at startup: the request timeout must fire.
  await proxy.set("blackhole");
  app = await launch();
  page = app.page;
  const hangStart = Date.now();
  // The splash stays up while the request hangs: capture it.
  await page
    .locator("main.screen[aria-busy='true']")
    .waitFor({ timeout: 10000 })
    .then(() => shot(page, "splash"))
    .catch(() => {});
  first = await firstScreen(page, 60000);
  check(
    "hanging_start_recovery_screen",
    first.screen === "lost",
    `${first.screen} après ${Date.now() - hangStart} ms`,
  );
  check("hanging_start_no_signin_form", !first.sawSignIn);
  check("hanging_start_token_kept", vaultEntries() === vaultSignedIn);
  await shot(page, "hanging-start");
  await proxy.set("up");
  await page.getByRole("button", { name: "Réessayer" }).click();
  check("hanging_manual_return", (await waitAppUsable(page, 20000)) !== null);

  // 4. Server lost while browsing.
  await openLibrary(page, "Films");
  const scroller = page.locator("main.content");
  await scroller.evaluate((el) =>
    el.scrollTo({ top: Math.min(600, el.scrollHeight) }),
  );
  await page.waitForTimeout(500);
  const scrollBefore = await scroller.evaluate((el) => el.scrollTop);
  const visibleCard = await page.evaluate(() => {
    const root = document.querySelector("main.content");
    const box = root.getBoundingClientRect();
    const card = [...document.querySelectorAll("ul.grid button.card")].find(
      (c) => {
        const r = c.getBoundingClientRect();
        return r.top >= box.top && r.bottom <= box.bottom;
      },
    );
    return card?.dataset.itemId ?? null;
  });
  await proxy.set("down");
  await page.locator(`[data-item-id="${visibleCard}"]`).click();
  await lostTitle(page).waitFor({ timeout: 25000 });
  check(
    "midsession_overlay_over_app",
    await page.evaluate(
      () =>
        !!document
          .querySelector('nav[aria-label="Bibliothèques"]')
          ?.closest("[inert]") && !!document.querySelector("#detail-title"),
    ),
  );
  await shot(page, "midsession-lost");
  const upAt2 = Date.now();
  await proxy.set("up");
  const back2 = await waitAppUsable(page, 40000);
  check(
    "midsession_automatic_return",
    back2 !== null,
    `${Date.now() - upAt2} ms`,
  );
  await page.locator(".detail button.play").waitFor({ timeout: 20000 });
  check("midsession_detail_reloaded", true, await text(detailTitle(page)));
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  const scrollAfter = await scroller.evaluate((el) => el.scrollTop);
  check(
    "midsession_scroll_kept",
    scrollAfter === scrollBefore,
    `${scrollBefore} → ${scrollAfter}`,
  );
  await shot(page, "midsession-back");

  // 5. Token revoked: every authenticated request gets 401.
  await proxy.set("reject");
  await page.getByRole("button", { name: "Accueil" }).click();
  await page.locator("#password").waitFor({ timeout: 20000 });
  await page.waitForTimeout(1500);
  check(
    "revoked_single_reauth_screen",
    (await signInHeading(page).count()) === 1,
    `${proxy.counts.rejected} requête(s) refusée(s)`,
  );
  check(
    "revoked_username_prefilled",
    (await page.locator("#username").inputValue()) === account.user,
  );
  check(
    "revoked_password_focused",
    await page.evaluate(() => document.activeElement?.id === "password"),
  );
  check("revoked_token_deleted", vaultEntries() === vaultSignedIn - 1);
  check(
    "revoked_app_kept_behind",
    await page.evaluate(
      () => !!document.querySelector('nav[aria-label="Bibliothèques"]'),
    ),
  );
  await shot(page, "reauth");
  await proxy.set("up");
  await page.locator("#password").fill(account.password);
  await page.getByRole("button", { name: "Se connecter", exact: true }).click();
  check("reauth_return", (await waitAppUsable(page, 20000)) !== null);
  check("reauth_token_saved", vaultEntries() === vaultSignedIn);
  await homeHeading(page).waitFor({ timeout: 20000 });

  // 6. Change server, then come back without a password.
  await accountAction(page, "Changer de serveur");
  // The session set aside is the newest tile of the shelf (ADR 0103).
  const comeBack = page
    .getByRole("button", { name: /^Se connecter à / })
    .first();
  await comeBack.waitFor({ timeout: 10000 });
  check(
    "change_server_offers_return",
    (await comeBack.getAttribute("data-access")) === "session",
    await comeBack.getAttribute("aria-label"),
  );
  check("change_server_token_kept", vaultEntries() === vaultSignedIn);
  await shot(page, "change-server");
  await comeBack.click();
  check("return_without_password", (await waitAppUsable(page, 20000)) !== null);
  check(
    "return_shelf_without_open_session",
    ((await invoke(page, "emby_shelf")).value ?? []).every(
      (tile) => tile.server.userName !== account.user,
    ),
  );

  // 7. Stream cut during playback.
  await openLibrary(page, "Films");
  const itemId = await cards(page).first().getAttribute("data-item-id");
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  const fromStartButton = page.locator(".detail button.from-start");
  check(
    "film_initial_state",
    true,
    `${await text(detailTitle(page))} — ${await text(play)}`,
  );
  // A resume point left by an interrupted run is cleared by this run's cleanup.
  await ((await fromStartButton.count()) ? fromStartButton : play).click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForDiagnostics(
    page,
    (d) => (d.duration ?? 0) > 0 && (d.timePos ?? 0) > 0.5,
  );
  const target = Math.round((started?.duration ?? 0) * 0.2);
  await page.evaluate(
    (s) =>
      window.__TAURI_INTERNALS__.invoke("player_command", {
        command: { type: "seekAbsolute", seconds: s },
      }),
    target,
  );
  const atCut = await waitForPosition(page, target + 3, 30000);
  await proxy.set("down");
  const cutAt = Date.now();
  const banner = page.getByText("Lecture interrompue.");
  let forced = false;
  try {
    await banner.waitFor({ timeout: 90000 });
  } catch {
    // Still playing from mpv's cache: a seek past it needs the network.
    forced = true;
    await page.evaluate(
      (s) =>
        window.__TAURI_INTERNALS__.invoke("player_command", {
          command: { type: "seekAbsolute", seconds: s },
        }),
      Math.round((started?.duration ?? 0) * 0.8),
    );
    await banner.waitFor({ timeout: 90000 });
  }
  const lastPos = atCut?.timePos ?? target;
  check(
    "stream_cut_interrupted",
    true,
    `${Date.now() - cutAt} ms après la coupure${forced ? " (après un saut hors cache)" : ""}`,
  );
  await lostTitle(page).waitFor({ timeout: 25000 });
  await shot(page, "playback-interrupted-lost");
  await proxy.set("up");
  check("after_cut_return", (await waitAppUsable(page, 40000)) !== null);
  await shot(page, "playback-interrupted-banner");
  const after = await invoke(page, "emby_item_detail", { itemId });
  check("after_cut_not_watched", after.ok && after.value.played === false);
  const saved = after.value?.resume?.positionSeconds ?? null;
  check(
    "after_cut_position_saved",
    saved !== null && saved >= target - 5,
    `serveur ${saved?.toFixed?.(1)} s, coupure vers ${lastPos.toFixed(1)} s`,
  );
  // The detail page behind the banner also offers "Reprendre".
  const bannerResume = page
    .locator(".banner")
    .getByRole("button", { name: "Reprendre" });
  check(
    "after_cut_resume_focused",
    await bannerResume.evaluate((el) => el === document.activeElement),
  );
  await bannerResume.click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const resumed = await waitForPosition(page, target - 5, 30000);
  check(
    "resumed_from_server_position",
    resumed?.ok &&
      resumed.timePos >= target - 5 &&
      resumed.timePos < (saved ?? target) + 30,
    resumed?.timePos?.toFixed(1),
  );
  // Cleanup: back to 0 so the stop report clears the resume point.
  await page.evaluate(() =>
    window.__TAURI_INTERNALS__.invoke("player_command", {
      command: { type: "seekAbsolute", seconds: 0 },
    }),
  );
  await waitForDiagnostics(
    page,
    (x) => (x.timePos ?? 99) < 3 && (x.timePos ?? 0) > 0,
  );
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });
  const cleared = await invoke(page, "emby_item_detail", { itemId });
  check(
    "cleanup_resume_cleared",
    cleared.ok && cleared.value.resume === null && !cleared.value.played,
  );
  await page.keyboard.press("Escape");

  // 8. Sign out, then leave the direct server address as last server.
  await accountAction(page, "Se déconnecter");
  await signInHeading(page).waitFor({ timeout: 15000 });
  check("signed_out_token_deleted", vaultEntries() === vaultBefore);
  await signIn(
    page,
    { url: account.url, user: account.user },
    account.password,
  );
  await libraryNav(page).waitFor({ timeout: 20000 });
  await accountAction(page, "Se déconnecter");
  await signInHeading(page).waitFor({ timeout: 15000 });
  check("exit_code_end", (await app.close()) === 0);
  app = null;
  check("end_vault_clean", vaultEntries() === vaultBefore);
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) await app.close().catch(() => {});
  await proxy.stop();
  finish();
}
