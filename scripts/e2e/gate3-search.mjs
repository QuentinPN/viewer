// Gate 3 search scenario on the real Emby test account, driven over CDP (ADR 0011):
// - "/" and Ctrl+F put the focus in the sidebar search field;
// - typing searches once after the pause (debounce), films and series in their grids;
// - the server ignores case and accents: "HEURE" finds what "heure" finds;
// - a result opens its detail page, Escape comes back with focus on the card;
// - episodes (ADR 0020): matched on their own title, a card opens the episode page,
//   "Épisode suivant" replaces it, Escape comes back to the card that opened it;
// - one letter makes no request, an unknown word shows the empty state;
// - Escape in the field returns to the view the search replaced, scroll kept.
// Read only on the server; ends signed out.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  resizeWindow,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-search");

const field = (page) => page.locator("#search-field");
const searchTitle = (page) => page.locator("#search-title");
const status = (page) => page.locator(".search .status");
const resultIds = (page, group) =>
  page
    .locator(`ul[aria-labelledby="search-${group}"] button.card`)
    .evaluateAll((els) => els.map((e) => e.getAttribute("data-item-id")));
const focusedItemId = (page) =>
  page.evaluate(
    () => document.activeElement?.getAttribute("data-item-id") ?? null,
  );
/** `emby_search` IPC requests since the last `countSearches` (resource timing). */
const searchCalls = (page) =>
  page.evaluate(
    () =>
      performance
        .getEntriesByType("resource")
        .filter((e) => e.name.includes("emby_search")).length,
  );
const countSearches = (page) =>
  page.evaluate(() => {
    performance.setResourceTimingBufferSize(2000);
    performance.clearResourceTimings();
  });

/** Waits until the status announces the results of `query`, after loading. */
const waitForResults = (page, query) =>
  page.waitForFunction(
    (q) => {
      const section = document.querySelector(".search");
      const text = section?.querySelector(".status")?.textContent ?? "";
      return (
        section?.getAttribute("aria-busy") === "false" &&
        text.includes(`« ${q} »`)
      );
    },
    query,
    { timeout: 15000 },
  );

const app = await launch();
try {
  const { page } = app;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
  await homeHeading(page).waitFor({ timeout: 20000 });
  await countSearches(page);

  // "/" from outside a text field.
  await libraryNav(page).locator("button.home-link").focus();
  await page.keyboard.press("/");
  check(
    "slash_focuses_field",
    await field(page).evaluate((e) => e === document.activeElement),
  );
  check("slash_not_typed", (await field(page).inputValue()) === "");

  // Typed at a human pace: one request after the pause.
  const started = Date.now();
  await page.keyboard.type("heure", { delay: 60 });
  await searchTitle(page).waitFor({ timeout: 5000 });
  await waitForResults(page, "heure");
  const elapsed = Date.now() - started;
  const series = await resultIds(page, "series");
  const movies = await resultIds(page, "movies");
  const seriesNames = await page
    .locator('ul[aria-labelledby="search-series"] button.card')
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));
  check(
    "one_request_after_typing",
    (await searchCalls(page)) === 1,
    `${await searchCalls(page)} requête(s)`,
  );
  check(
    "series_found",
    seriesNames.some((n) => n.includes("24 Heures chrono")),
    `${movies.length} film(s), ${series.length} série(s), ${elapsed} ms`,
  );
  check(
    "status_announced",
    (await status(page).getAttribute("role")) === "status",
    await status(page).textContent(),
  );
  check(
    "home_no_longer_current",
    (await libraryNav(page)
      .locator("button.home-link")
      .getAttribute("aria-current")) === null,
  );
  await shot(page, "results");

  // Case and accents: the server's results do not change.
  await field(page).fill("");
  await homeHeading(page).waitFor({ timeout: 5000 });
  await field(page).focus();
  await page.keyboard.type("HÉURE", { delay: 30 });
  await waitForResults(page, "HÉURE");
  check(
    "case_and_accents_ignored",
    JSON.stringify(await resultIds(page, "series")) ===
      JSON.stringify(series) &&
      JSON.stringify(await resultIds(page, "movies")) ===
        JSON.stringify(movies),
  );

  // A result opens its detail page; Escape comes back to it.
  const first = cards(page).first();
  const firstId = await first.getAttribute("data-item-id");
  await first.focus();
  await page.keyboard.press("Enter");
  await detailTitle(page).waitFor({ timeout: 15000 });
  await shot(page, "detail-from-search");
  await page.keyboard.press("Escape");
  await searchTitle(page).waitFor({ state: "visible", timeout: 5000 });
  check("focus_back_on_card", (await focusedItemId(page)) === firstId, firstId);
  check("query_kept", (await field(page).inputValue()) === "HÉURE");

  // Episodes: their own title matches, a card opens the episode page.
  const episodes = page.locator(
    'ul[aria-labelledby="search-episodes"] button.card',
  );
  const episodeTitles = await episodes.evaluateAll((els) =>
    els.map((e) => e.querySelector(".title")?.textContent?.trim() ?? ""),
  );
  check(
    "episodes_match_their_title",
    episodeTitles.length > 0 &&
      episodeTitles.every((t) =>
        t
          .normalize("NFD")
          .replace(/\p{M}/gu, "")
          .toLowerCase()
          .match(/(^|[^\p{L}\p{N}])heure/u),
      ),
    `${episodeTitles.length} épisode(s) : ${episodeTitles.slice(0, 4).join(" | ")}`,
  );
  const episodeCard = episodes.first();
  const episodeId = await episodeCard.getAttribute("data-item-id");
  const episodeLabel = await episodeCard.getAttribute("aria-label");
  check(
    "episode_card_opens",
    episodeLabel?.startsWith("Ouvrir la fiche de ") ?? false,
    episodeLabel,
  );
  await episodeCard.focus();
  await page.keyboard.press("Enter");
  const episodeTitle = page.locator("#episode-title");
  await episodeTitle.waitFor({ timeout: 15000 });
  await page.waitForFunction(
    () => document.querySelector(".adjacent, .tech") !== null,
    null,
    { timeout: 15000 },
  );
  const openedTitle = (await episodeTitle.textContent()).trim();
  check("episode_page_opened", openedTitle === episodeTitles[0], openedTitle);
  await shot(page, "episode-from-search");
  const next = page.locator(".adjacent button.next");
  if ((await next.count()) > 0) {
    await next.focus();
    await page.keyboard.press("Enter");
    await page.waitForFunction(
      (before) => {
        const h = document.querySelector("#episode-title");
        return h && h.textContent.trim() !== before;
      },
      openedTitle,
      { timeout: 15000 },
    );
    await page.waitForFunction(
      () =>
        document.activeElement?.classList.contains("next") ||
        document.activeElement?.classList.contains("previous"),
      null,
      { timeout: 15000 },
    );
    check("next_episode_in_place", true, await episodeTitle.textContent());
  } else {
    check("next_episode_in_place", true, "pas d'épisode suivant");
  }
  await page.keyboard.press("Escape");
  await searchTitle(page).waitFor({ state: "visible", timeout: 5000 });
  check(
    "focus_back_on_episode_card",
    (await focusedItemId(page)) === episodeId,
    episodeId,
  );

  // One letter: nothing sent, the status says why.
  await countSearches(page);
  await field(page).fill("a");
  await page.waitForTimeout(600);
  check("one_letter_no_request", (await searchCalls(page)) === 0);
  check(
    "one_letter_hint",
    (await status(page).textContent()).includes("au moins 2 caractères"),
  );

  // Nothing found.
  await field(page).fill("zzqxwv");
  await field(page).press("Enter");
  await waitForResults(page, "zzqxwv");
  const empty = page.locator(".search .state-title");
  check(
    "empty_state",
    (await empty.textContent()).includes("Aucun film, série ou épisode"),
  );
  await shot(page, "empty");

  // Escape in the field: back home.
  await field(page).focus();
  await page.keyboard.press("Escape");
  await homeHeading(page).waitFor({ state: "visible", timeout: 5000 });
  check(
    "escape_back_home",
    (await field(page).inputValue()) === "" &&
      (await libraryNav(page)
        .locator("button.home-link")
        .getAttribute("aria-current")) === "page",
  );

  // From a scrolled library: Ctrl+F, search, Escape → same library, same scroll.
  const library = libraryNav(page).locator("ul.libraries button").first();
  const libraryName = await library.evaluate(
    (e) => e.lastChild?.textContent?.trim() ?? "",
  );
  await library.click();
  await cards(page).nth(30).waitFor({ timeout: 20000 });
  const content = page.locator("main.content");
  // Focus a card down the grid: the browser scrolls it into view.
  await cards(page).nth(30).focus();
  await page.waitForTimeout(200);
  const scrolled = await content.evaluate((e) => e.scrollTop);
  check("library_scrolled", scrolled > 300, `${scrolled} px`);
  await page.keyboard.press("Control+f");
  check(
    "ctrl_f_focuses_field",
    await field(page).evaluate((e) => e === document.activeElement),
  );
  await page.keyboard.type("the", { delay: 30 });
  await waitForResults(page, "the");
  check("results_from_top", (await content.evaluate((e) => e.scrollTop)) === 0);
  const more = await page.locator(".search .more").count();
  check("more_results_hint", true, `${more} message(s) « précisez »`);
  await page.keyboard.press("Escape");
  await page
    .locator("main.content h1")
    .filter({ hasText: libraryName })
    .waitFor({ timeout: 5000 });
  await page.waitForTimeout(200);
  const restored = await content.evaluate((e) => e.scrollTop);
  check(
    "library_scroll_restored",
    Math.abs(restored - scrolled) < 2,
    `${scrolled} → ${restored}`,
  );

  // Narrow window: the field stays usable, no horizontal overflow.
  resizeWindow(app.proc.pid, 700, 700);
  await page.waitForFunction(() => window.innerWidth < 720);
  await field(page).fill("heure");
  await waitForResults(page, "heure");
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  const fieldWidth = await field(page).evaluate(
    (e) => e.getBoundingClientRect().width,
  );
  check(
    "narrow_no_overflow",
    !overflow && fieldWidth >= 120,
    `champ ${Math.round(fieldWidth)} px`,
  );
  await shot(page, "narrow");
  await field(page).fill("");
  resizeWindow(app.proc.pid, 1280, 800);

  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  finish();
}
