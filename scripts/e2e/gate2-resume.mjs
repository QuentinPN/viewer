// Gate 2 reporting and resume scenario on the real Emby test account, driven over
// CDP (ADR 0008). Writes playback positions to the server and clears them again:
// - film: play, seek to ~20 %, Escape → the detail page offers "Reprendre" and
//   "Lire depuis le début"; Enter on "Reprendre" starts near the saved position;
//   "Lire depuis le début" from 0 then Escape → the resume point is gone;
// - episode: play, seek to ~20 %, close the window during playback (the stop report
//   is flushed before exit), relaunch → the episode offers "Reprendre"; resume, seek
//   back to 0, ✕ → "Lire" again.
// Starts in any state, ends signed out with no resume point left on either item.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  libraryNav,
  playerBadge,
  signIn,
  signOut,
  startScreen,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate2-resume");

const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__TAURI_INTERNALS__.invoke("player_command", { command: c }),
    cmd,
  );

/** Waits for playback, seeks to 20 % of the duration and waits to get there. */
async function seekToFifth(page, label) {
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForDiagnostics(
    page,
    (d) => (d.duration ?? 0) > 0 && (d.timePos ?? 0) > 0.5,
  );
  const target = Math.round((started?.duration ?? 0) * 0.2);
  await command(page, { type: "seekAbsolute", seconds: target });
  const there = await waitForPosition(page, target);
  check(
    `${label}_seek_fifth`,
    there?.ok && there.timePos >= target,
    there?.ok
      ? `${there.timePos.toFixed(1)} / ${there.duration.toFixed(1)} s`
      : there?.error,
  );
  return target;
}

/** Started from the saved position, not from 0. */
async function checkResumed(page, label, target) {
  await playerBadge(page).waitFor({ timeout: 20000 });
  const d = await waitForPosition(page, target - 3);
  check(
    `${label}_resumed_at_position`,
    d?.ok && d.timePos >= target - 3 && d.timePos < target + 30,
    d?.ok ? `${d.timePos.toFixed(1)} s, attendu ≈ ${target} s` : d?.error,
  );
}

/** Seeks back to the start so the stop report clears the resume point. */
async function rewind(page, label) {
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const d = await waitForDiagnostics(
    page,
    (x) => (x.timePos ?? 99) < 3 && (x.timePos ?? 0) > 0,
  );
  check(`${label}_rewound`, d?.ok && d.timePos < 3, d?.timePos?.toFixed(1));
}

async function openLibrary(page, kind) {
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf(kind)).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
}

async function ensureSignedIn(page) {
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
}

const text = async (locator) => (await locator.textContent())?.trim() ?? "";

let app = await launch();
try {
  let page = app.page;
  await ensureSignedIn(page);

  // Film.
  await openLibrary(page, "Films");
  const play = page.locator(".detail button.play");
  const fromStart = page.locator(".detail button.from-start");
  const resumeInfo = page.locator(".detail .status .resume");
  await play.waitFor({ timeout: 15000 });
  const title = await text(detailTitle(page));
  check("film_initial_state", (await text(play)) === "Lecture", title);
  await play.focus();
  await page.keyboard.press("Enter");
  const filmTarget = await seekToFifth(page, "film");
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  await page
    .locator(".detail button.play", { hasText: "Reprendre" })
    .waitFor({ timeout: 15000 });
  check("film_offers_resume", true, await text(resumeInfo));
  check("film_offers_from_start", (await fromStart.count()) === 1);
  check(
    "film_focus_kept",
    await page.evaluate(() =>
      document.activeElement?.matches(".detail button.play"),
    ),
  );
  await shot(page, "film-resume-offered");

  await page.keyboard.press("Enter");
  await checkResumed(page, "film", filmTarget);
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Reprendre" }).waitFor({ timeout: 15000 });

  // Cleanup: from the start, stop near 0.
  await fromStart.focus();
  await page.keyboard.press("Enter");
  await playerBadge(page).waitFor({ timeout: 20000 });
  const fromZero = await waitForPosition(page, 0.5);
  check(
    "film_from_start_at_zero",
    fromZero?.ok && fromZero.timePos < 10,
    fromZero?.timePos?.toFixed(1),
  );
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });
  check("film_resume_cleared", (await resumeInfo.count()) === 0);
  check("film_from_start_removed", (await fromStart.count()) === 0);
  check(
    "film_focus_moved_to_play",
    await page.evaluate(() =>
      document.activeElement?.matches(".detail button.play"),
    ),
    await page.evaluate(() => document.activeElement?.outerHTML.slice(0, 80)),
  );
  await shot(page, "film-cleared");
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });

  // Episode: seek, then close the window during playback.
  await openLibrary(page, "Séries");
  const episode = page.locator("ol.list li.episode button.thumb").first();
  await episode.waitFor({ timeout: 20000 });
  const initialLabel = await episode.getAttribute("aria-label");
  check(
    "episode_initial_state",
    /^Lire /.test(initialLabel ?? ""),
    initialLabel,
  );
  await episode.focus();
  await page.keyboard.press("Enter");
  const episodeTarget = await seekToFifth(page, "episode");
  check("close_during_playback_exit_code", (await app.close()) === 0);

  app = await launch();
  page = app.page;
  await ensureSignedIn(page);
  await openLibrary(page, "Séries");
  const resumable = page
    .locator("ol.list li.episode button.thumb")
    .first()
    .and(page.locator('[aria-label^="Reprendre "]'));
  await resumable.waitFor({ timeout: 20000 });
  check(
    "episode_resume_after_close",
    true,
    await resumable.getAttribute("aria-label"),
  );
  check(
    "episode_progress_bar",
    (await page
      .locator("ol.list li.episode")
      .first()
      .locator(".progress")
      .count()) === 1,
  );
  await resumable.scrollIntoViewIfNeeded();
  await shot(page, "episode-resume-offered");

  await resumable.focus();
  await page.keyboard.press("Enter");
  await checkResumed(page, "episode", episodeTarget);
  await rewind(page, "episode");
  // The controls fade after 3 s (ADR 0017): move the mouse first, as a user would.
  await page.mouse.move(400, 300);
  await page.mouse.move(420, 320);
  await page.getByRole("button", { name: "Revenir", exact: true }).click();
  const cleared = page
    .locator("ol.list li.episode button.thumb")
    .first()
    .and(page.locator('[aria-label^="Lire "]'));
  await cleared.waitFor({ timeout: 15000 });
  check(
    "episode_resume_cleared",
    true,
    await cleared.getAttribute("aria-label"),
  );
  check(
    "episode_focus_back",
    await page.evaluate(() =>
      document.activeElement?.matches("li.episode button.thumb"),
    ),
  );
  await cleared.scrollIntoViewIfNeeded();
  await shot(page, "episode-cleared");

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
