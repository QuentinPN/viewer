// Gate 3 home hero on the real Emby test account, driven over CDP:
// - the hero shows `emby_hero`'s titles in order, one at a time, with its dots;
// - nothing changes on its own; arrows, dots, ← and → change the title, the focus
//   follows on the dots and on the buttons;
// - the main button reads "Lecture"/"Reprendre" as the server says; "Plus d'infos"
//   opens the detail page of the film or series;
// - the main button plays at once; stopped at the start, the hero is unchanged;
// - reduced motion removes the fade.
// Starts a playback and stops it within seconds (no resume point is kept).
import { launch } from "./app.mjs";
import {
  createReport,
  detailTitle,
  homeHeading,
  playerBadge,
  signIn,
  signOut,
  startScreen,
  testAccount,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-hero");

const serverHero = async (page) => {
  const r = await page.evaluate(() =>
    window.__TAURI_INTERNALS__
      .invoke("emby_hero")
      .then((items) => ({ items }))
      .catch((e) => ({ error: JSON.stringify(e) })),
  );
  if (r.error) throw new Error(`emby_hero : ${r.error}`);
  return r.items;
};
const hero = (page) => page.locator("section.hero");
const shownId = (page) =>
  hero(page).locator(".slide").getAttribute("data-item-id");
const activeDot = (page) =>
  hero(page)
    .locator(".dot")
    .evaluateAll((els) =>
      els.findIndex((e) => e.getAttribute("aria-current") === "true"),
    );
const focusedClass = (page) =>
  page.evaluate(() => document.activeElement?.className ?? "");
/** Title text, or the alt text of the logo that replaces it. */
const titleText = (locator) =>
  locator.evaluate(
    (h) => h.textContent.trim() || h.querySelector("img")?.alt || "",
  );

async function homeLoaded(page) {
  await homeHeading(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => !document.querySelector(".home .skeleton, .home .hero-skeleton"),
    null,
    { timeout: 30000 },
  );
}

const app = await launch();
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(
      page,
      { url: account.url, user: account.user },
      account.password,
    );
  }
  await homeLoaded(page);

  // 1. Titles of the server, in order, one at a time.
  const items = await serverHero(page);
  const ids = items.map((i) => i.detail.id);
  check(
    "hero_matches_server",
    items.length > 0 && (await shownId(page)) === ids[0],
    items
      .map((i) => `${i.reason}:${i.detail.itemType}:${i.detail.name}`)
      .join(" | "),
  );
  check(
    "one_title_and_dots",
    (await hero(page).locator(".slide").count()) === 1 &&
      (await hero(page).locator(".dot").count()) ===
        (items.length > 1 ? items.length : 0),
  );
  check(
    "carousel_semantics",
    (await hero(page).getAttribute("aria-roledescription")) === "carrousel" &&
      (await hero(page).locator(".slide").getAttribute("aria-label")) ===
        `1 sur ${items.length}`,
  );
  const first = items[0];
  const firstTitle = await titleText(hero(page).locator("h2.title"));
  check("title_or_logo", firstTitle === first.detail.name, firstTitle);
  const expected =
    first.playId === null ? null : first.resume ? "Reprendre" : "Lecture";
  const playButton = hero(page).locator(".slide button.play");
  check(
    "main_button_label",
    expected === null
      ? (await playButton.count()) === 0
      : (await playButton.textContent()).trim() === expected &&
          (await playButton.getAttribute("aria-label")).startsWith(
            `${expected} ${first.detail.name}`,
          ),
    expected ?? "rien à lire",
  );
  await shot(page, "hero");

  // 2. Nothing moves on its own.
  await page.waitForTimeout(7000);
  check("no_auto_rotation", (await shownId(page)) === ids[0]);

  if (items.length > 1) {
    // 3. Arrows and dots.
    await hero(page).hover();
    await hero(page).getByRole("button", { name: "Titre suivant" }).click();
    check(
      "next_arrow",
      (await shownId(page)) === ids[1] && (await activeDot(page)) === 1,
    );
    await shot(page, "hero-second");
    await hero(page).getByRole("button", { name: "Titre précédent" }).click();
    await hero(page).getByRole("button", { name: "Titre précédent" }).click();
    check("previous_wraps", (await shownId(page)) === ids.at(-1));
    await hero(page).locator(".dot").first().click();
    check("dot_shows_title", (await shownId(page)) === ids[0]);

    // 4. Keyboard: the focus follows on the dots and on the buttons.
    await hero(page).locator(".dot").first().focus();
    await page.keyboard.press("ArrowRight");
    check(
      "arrow_key_on_dots",
      (await shownId(page)) === ids[1] &&
        (await page.evaluate(
          () => document.activeElement?.getAttribute("aria-current") === "true",
        )),
    );
    await hero(page).locator(".slide button.info").focus();
    await page.keyboard.press("ArrowLeft");
    check(
      "arrow_key_on_buttons",
      (await shownId(page)) === ids[0] &&
        (await focusedClass(page)).includes("info"),
      await focusedClass(page),
    );
  } else {
    check("single_title", true, "un seul titre : ni flèches ni points");
  }

  // 5. "Plus d'infos" opens the detail page of the film or series.
  await hero(page).locator(".slide button.info").click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  const detailName = await titleText(detailTitle(page));
  check("more_info_opens_detail", detailName === first.detail.name, detailName);
  await shot(page, "detail-from-hero");
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  await homeLoaded(page);

  // 6. Reduced motion: no fade.
  const fade = () =>
    hero(page)
      .locator(".slide")
      .evaluate((e) => getComputedStyle(e).animationDuration);
  const normal = await fade();
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.waitForTimeout(100);
  const reduced = await fade();
  await page.emulateMedia({ reducedMotion: "no-preference" });
  check(
    "reduced_motion",
    normal === "0.2s" && reduced === "0s",
    `${normal} → ${reduced}`,
  );

  // 7. The main button plays at once; stopped at the start, nothing is kept.
  const playable = items.findIndex((i) => i.playId !== null && !i.resume);
  if (playable >= 0) {
    const target = items[playable];
    await hero(page)
      .locator(".dot")
      .nth(playable)
      .click()
      .catch(() => {});
    await hero(page).locator(".slide button.play").click();
    await playerBadge(page).waitFor({ timeout: 20000 });
    const started = await waitForPosition(page, 1, 20000);
    check(
      "main_button_plays",
      started?.ok && started.timePos > 1,
      `${target.detail.name} : ${started?.timePos?.toFixed(1)} s`,
    );
    await shot(page, "playing-from-hero");
    await page.keyboard.press("Escape");
    await homeLoaded(page);
    await page.waitForTimeout(1500);
    const after = await serverHero(page);
    const again = after.find((i) => i.detail.id === target.detail.id);
    check(
      "hero_unchanged_after_short_play",
      after.map((i) => i.detail.id).join() === ids.join() &&
        again?.resume === null,
      after.map((i) => i.detail.name).join(" | "),
    );
    check(
      "shown_title_kept_after_reload",
      (await shownId(page)) === target.detail.id,
    );
  } else {
    check("play_skipped", true, "aucun titre à lire sans reprise");
  }

  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  finish();
}
