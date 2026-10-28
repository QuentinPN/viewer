// Gate 2 playback scenario on the real Emby server, driven over CDP (ADR 0006,
// ADR 0007): a film from its detail page by keyboard, then an episode from its
// series. For each: the server's decision shown in the player, playback advancing
// (engine diagnostics), Escape or ✕ back to the detail page with focus on what
// started it, player closed. Audio goes to `ao=null` (VIEWER_E2E_SILENT). Screenshots
// show the HTML overlay only, never the video. Starts in any state, ends signed out.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate2-play");

const badge = (page) => page.locator('[title="Décision du serveur Emby"]');
const diagnostics = (page) =>
  page.evaluate(() =>
    window.__TAURI_INTERNALS__
      .invoke("player_diagnostics")
      .then((d) => ({ ok: true, ...d }))
      .catch((e) => ({ ok: false, error: String(e) })),
  );

/** Waits until the engine reports a position past `seconds`. */
async function waitForPosition(page, seconds, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await diagnostics(page);
    if (last.ok && (last.timePos ?? 0) > seconds) return last;
    await page.waitForTimeout(250);
  }
  return last;
}

/** Started from the focused control: player shown, method, playback advancing. */
async function checkPlaying(page, label) {
  await badge(page).waitFor({ timeout: 20000 });
  const method = (await badge(page).textContent()).trim();
  check(
    `${label}_server_decision`,
    method.startsWith("Lecture directe"),
    method,
  );
  check(
    `${label}_library_hidden`,
    await page.evaluate(
      () => document.querySelector(".layer")?.hidden === true,
    ),
  );
  const first = await waitForPosition(page, 0.5);
  const second = await waitForPosition(page, (first?.timePos ?? 0) + 1.5);
  check(
    `${label}_playback_advances`,
    second?.ok && second.timePos > (first?.timePos ?? 0),
    second?.ok
      ? `${first.timePos.toFixed(1)} → ${second.timePos.toFixed(1)} s · ${second.videoCodec} ${second.videoWidth}×${second.videoHeight} · hwdec ${second.hwdecCurrent} · ${second.container}`
      : second?.error,
  );
  await shot(page, `${label}-playing`);
}

/** Back on the detail page, focus on `selector`, engine closed. */
async function checkBack(page, label, selector) {
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  check(
    `${label}_back_focus`,
    await page.evaluate((s) => document.activeElement?.matches(s), selector),
    await page.evaluate(() => document.activeElement?.outerHTML.slice(0, 80)),
  );
  const after = await diagnostics(page);
  check(`${label}_player_closed`, !after.ok, after.error ?? "still open");
}

let app = await launch();
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );

  // Film: detail, Enter on "Lecture", Escape back.
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  check("film_play_button", true, (await play.textContent()).trim());
  await play.focus();
  await page.keyboard.press("Enter");
  await checkPlaying(page, "film");
  await page.keyboard.press("Escape");
  await checkBack(page, "film", ".detail button.play");
  await shot(page, "film-back");
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });

  // Episode: series detail, Enter on the first episode, ✕ back.
  await libraries.nth(kinds.indexOf("Séries")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  const episode = page.locator("ol.list li.episode button.thumb").first();
  await episode.waitFor({ timeout: 20000 });
  const episodeLabel = await episode.getAttribute("aria-label");
  await episode.focus();
  await page.keyboard.press("Enter");
  await checkPlaying(page, "episode");
  await page
    .getByRole("button", { name: "Arrêter et revenir à la fiche" })
    .click();
  await checkBack(page, "episode", "li.episode button.thumb");
  check("episode_label", /^Lire /.test(episodeLabel ?? ""), episodeLabel);
  await shot(page, "episode-back");

  await page.keyboard.press("Escape");
  await signOut(page);
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
