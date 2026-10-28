// Gate 3 home page scenario on the real Emby test account, driven over CDP (ADR 0010):
// - the home page is the default view, with one "Ajoutés récemment" row per library;
// - Left/Right move between the cards of a row, the arrow buttons scroll it;
// - "Tout voir" opens the library grid, "Accueil" comes back;
// - a latest film opens its detail page; playing it to ~20 % then Escape puts it in
//   "Reprendre"; activating that card resumes near the saved position;
// - cleanup: seek back to 0 and stop, the film leaves "Reprendre".
// Writes one playback position to the server and clears it again; ends signed out.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  homeHeading,
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
const { check, shot, finish } = createReport("gate3-home");

const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__TAURI_INTERNALS__.invoke("player_command", { command: c }),
    cmd,
  );

const latestRows = (page) =>
  page.locator("section.row", {
    has: page.locator("h2", { hasText: "Ajoutés récemment" }),
  });
const latestRow = (page, libraryName) =>
  page.locator("section.row", {
    has: page.locator("h2", {
      hasText: new RegExp(`^Ajoutés récemment · ${libraryName}$`),
    }),
  });
const resumeRow = (page) =>
  page.locator("section.row", {
    has: page.locator("h2", { hasText: /^Reprendre$/ }),
  });
const focusedItemId = (page) =>
  page.evaluate(
    () => document.activeElement?.getAttribute("data-item-id") ?? null,
  );

const app = await launch();
try {
  const { page } = app;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }

  // Default view.
  await homeHeading(page).waitFor({ timeout: 20000 });
  const homeLink = libraryNav(page).locator("button.home-link");
  check(
    "home_is_default",
    (await homeLink.getAttribute("aria-current")) === "page",
  );
  const libraryButtons = libraryNav(page).locator("ul.libraries button");
  const names = await libraryButtons.evaluateAll((els) =>
    els.map((e) => e.lastChild?.textContent?.trim() ?? ""),
  );
  await page.waitForFunction(
    () => !document.querySelector(".home .skeleton"),
    null,
    { timeout: 20000 },
  );
  const rowTitles = await latestRows(page)
    .locator("h2")
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));
  check(
    "latest_row_per_library",
    rowTitles.length === names.length,
    rowTitles.join(" | "),
  );
  const resumeBefore = await resumeRow(page)
    .locator("button.card")
    .evaluateAll((els) => els.map((e) => e.getAttribute("data-item-id")));
  check("resume_row_before", true, `${resumeBefore.length} élément(s)`);
  await shot(page, "home");

  // Keyboard inside a row, then the scroll arrows.
  const films = latestRow(page, "Films");
  const filmCards = films.locator("button.card");
  await filmCards.first().waitFor({ timeout: 20000 });
  const firstId = await filmCards.first().getAttribute("data-item-id");
  const secondId = await filmCards.nth(1).getAttribute("data-item-id");
  await filmCards.first().focus();
  await page.keyboard.press("ArrowRight");
  check("arrow_right_moves_focus", (await focusedItemId(page)) === secondId);
  await page.keyboard.press("ArrowLeft");
  check("arrow_left_moves_focus", (await focusedItemId(page)) === firstId);
  const right = films.getByRole("button", { name: /vers la droite/ });
  const left = films.getByRole("button", { name: /vers la gauche/ });
  check("left_arrow_disabled_at_start", await left.isDisabled());
  if (await right.isEnabled()) {
    await right.click();
    await page.waitForFunction(
      () => {
        const h = [...document.querySelectorAll("section.row h2")].find((e) =>
          e.textContent.trim().endsWith("· Films"),
        );
        return (
          (h?.closest("section").querySelector("ul.cards").scrollLeft ?? 0) > 0
        );
      },
      null,
      { timeout: 5000 },
    );
    // The arrows follow the scroll events, a frame after scrollLeft moved.
    const enabled = await left
      .and(page.locator(":enabled"))
      .waitFor({ timeout: 3000 })
      .then(() => true)
      .catch(() => false);
    check("right_arrow_scrolls", enabled);
  } else {
    check("right_arrow_scrolls", true, "rangée entièrement visible");
  }

  // "Tout voir" opens the library, "Accueil" comes back.
  await films.getByRole("button", { name: "Tout voir dans Films" }).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  check(
    "see_all_opens_library",
    (await libraryButtons
      .nth(names.indexOf("Films"))
      .getAttribute("aria-current")) === "page" &&
      (await homeHeading(page).count()) === 0,
  );
  await shot(page, "see-all");
  await homeLink.click();
  await homeHeading(page).waitFor({ timeout: 10000 });
  await filmCards.first().waitFor({ timeout: 20000 });
  check(
    "home_link_back",
    (await homeLink.getAttribute("aria-current")) === "page",
  );

  // Latest film → detail → play to ~20 % → back: it is in "Reprendre".
  const filmId = await filmCards.first().getAttribute("data-item-id");
  await filmCards.first().click();
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  const title = (await detailTitle(page).textContent()).trim();
  check("latest_opens_detail", true, title);
  await play.focus();
  await page.keyboard.press("Enter");
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForDiagnostics(
    page,
    (d) => (d.duration ?? 0) > 0 && (d.timePos ?? 0) > 0.5,
  );
  const target = Math.round((started?.duration ?? 0) * 0.2);
  await command(page, { type: "seekAbsolute", seconds: target });
  const there = await waitForPosition(page, target);
  check(
    "seek_fifth",
    there?.ok && there.timePos >= target,
    there?.timePos?.toFixed(1),
  );
  await page.keyboard.press("Escape");
  await play.filter({ hasText: "Reprendre" }).waitFor({ timeout: 15000 });
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  check(
    "detail_back_focus_on_card",
    (await focusedItemId(page)) === filmId,
    await focusedItemId(page),
  );

  const resumeCard = resumeRow(page).locator(
    `button.card[data-item-id="${filmId}"]`,
  );
  await resumeCard.waitFor({ timeout: 15000 });
  const label = await resumeCard.getAttribute("aria-label");
  check("film_in_resume_row", label?.startsWith("Reprendre "), label);
  check(
    "resume_first",
    (await resumeRow(page)
      .locator("button.card")
      .first()
      .getAttribute("data-item-id")) === filmId,
  );
  check(
    "resume_progress_bar",
    (await resumeCard.locator(".progress").count()) === 1,
  );
  await resumeRow(page).scrollIntoViewIfNeeded();
  const artwork = await page
    .waitForFunction(
      (id) => {
        const img = document.querySelector(
          `section.row button.card[data-item-id="${id}"] img`,
        );
        return img?.complete && img.naturalWidth > 0
          ? `${img.naturalWidth}×${img.naturalHeight}`
          : false;
      },
      filmId,
      { timeout: 15000 },
    )
    .then((h) => h.jsonValue())
    .catch(() => null);
  check("resume_card_artwork", artwork !== null, artwork ?? "pas d'image");
  await shot(page, "resume-row");

  // The card plays at once, from the saved position.
  await resumeCard.focus();
  await page.keyboard.press("Enter");
  await playerBadge(page).waitFor({ timeout: 20000 });
  const resumed = await waitForPosition(page, target - 3);
  check(
    "card_resumes_at_position",
    resumed?.ok &&
      resumed.timePos >= target - 3 &&
      resumed.timePos < target + 30,
    resumed?.ok
      ? `${resumed.timePos.toFixed(1)} s, attendu ≈ ${target} s`
      : resumed?.error,
  );
  await page.waitForTimeout(1500);
  const slider = await page.evaluate(() => {
    const r = document.querySelector("input.seek");
    return r ? `${r.value} / ${r.max}` : null;
  });
  check("seek_slider_value", true, slider);
  await shot(page, "playing-from-home");

  // Cleanup: back to 0, stop → no resume point left.
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const rewound = await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
  );
  check(
    "rewound",
    rewound?.ok && rewound.timePos < 3,
    rewound?.timePos?.toFixed(1),
  );
  await page.keyboard.press("Escape");
  await homeHeading(page).waitFor({ state: "visible", timeout: 10000 });
  await page.waitForFunction(
    (id) =>
      !document.querySelector(
        `section.row button.card[data-item-id="${id}"][aria-label^="Reprendre "]`,
      ),
    filmId,
    { timeout: 15000 },
  );
  const resumeAfter = await resumeRow(page)
    .locator("button.card")
    .evaluateAll((els) => els.map((e) => e.getAttribute("data-item-id")));
  check(
    "resume_cleared",
    resumeAfter.length === resumeBefore.length && !resumeAfter.includes(filmId),
    `${resumeAfter.length} élément(s)`,
  );
  await shot(page, "home-cleared");

  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  finish();
}
