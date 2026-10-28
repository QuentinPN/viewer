// Gate 3 sort and filters of a library grid (ADR 0013), on the real Emby test
// account, driven over CDP. Read only: nothing is written to the server.
// - defaults: title A→Z, nothing filtered, no "Réinitialiser";
// - each sort orders the grid as the server does (years checked on the cards);
// - a filter with no match shows its own empty state, whose button keeps the sort;
// - a genre narrows the count; favorites toggle (aria-pressed);
// - "En cours" for films only; choices kept per library and across a restart;
// - quick successive changes: the grid matches the last choice only;
// - the keyboard changes a select; cleanup leaves the defaults.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-sort-filter");

const toolbar = (page) => page.locator('.toolbar[aria-label="Tri et filtres"]');
const select = (page, label) =>
  toolbar(page)
    .locator("label.field", { has: page.locator(`span:text-is("${label}")`) })
    .locator("select");
const favorites = (page) =>
  toolbar(page).getByRole("button", { name: "Favoris" });
const resetButton = (page) =>
  toolbar(page).getByRole("button", { name: "Réinitialiser" });
const countText = (page) => page.locator(".heading .count").textContent();
const countOf = async (page) =>
  Number((await countText(page)).replace(/\D/g, "")) || 0;

/** Waits until the first page for the current options is in. */
async function settled(page) {
  await page.waitForFunction(
    () =>
      document.querySelector("main.content")?.getAttribute("aria-busy") ===
        "false" &&
      /\d/.test(document.querySelector(".heading .count")?.textContent ?? ""),
    null,
    { timeout: 20000 },
  );
}

const years = (page) =>
  cards(page)
    .locator(".meta")
    .evaluateAll((els) =>
      els
        .map((e) => /^(\d{4})/.exec(e.textContent.trim())?.[1])
        .filter(Boolean)
        .map(Number),
    );
const titles = (page) =>
  cards(page)
    .locator(".title")
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));
const sorted = (xs, cmp) => xs.every((x, i) => i === 0 || cmp(xs[i - 1], x));

async function openLibrary(page, name) {
  await libraryNav(page)
    .locator("ul.libraries button", { hasText: name })
    .first()
    .click();
  await page
    .locator(".heading h1", { hasText: new RegExp(`^${name}$`) })
    .waitFor({ timeout: 20000 });
  await toolbar(page).waitFor({ timeout: 20000 });
  await settled(page);
}

let app = await launch();
try {
  let { page } = app;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await libraryNav(page).waitFor({ timeout: 20000 });
  const libraryNames = await libraryNav(page)
    .locator("ul.libraries button")
    .evaluateAll((els) =>
      els.map((e) => ({
        kind: e.querySelector(".kind")?.textContent.trim(),
        name: e.lastChild?.textContent?.trim() ?? "",
      })),
    );
  const films = libraryNames.find((l) => l.kind === "Films")?.name;
  const series = libraryNames.find((l) => l.kind === "Séries")?.name;
  check("libraries_found", films && series, `${films} / ${series}`);

  // A previous interrupted run may have left choices: start from defaults.
  await openLibrary(page, films);
  if (await resetButton(page).isVisible()) {
    await resetButton(page).click();
    await settled(page);
  }
  const total = await countOf(page);
  check(
    "defaults",
    (await select(page, "Trier par").inputValue()) === "name" &&
      (await select(page, "Ordre").inputValue()) === "asc" &&
      (await select(page, "Visionnage").inputValue()) === "all" &&
      (await select(page, "Genre").inputValue()) === "" &&
      (await favorites(page).getAttribute("aria-pressed")) === "false" &&
      !(await resetButton(page).isVisible()),
    `${total} films`,
  );
  // Emby sorts on SortName (articles dropped): the default first page is the
  // reference for later comparisons.
  const t = await titles(page);
  check(
    "first_page",
    t.length === Math.min(60, total),
    t.slice(0, 3).join(" | "),
  );
  await shot(page, "defaults");

  // Year: newest first by default, then reversed.
  await select(page, "Trier par").selectOption("year");
  await settled(page);
  let ys = await years(page);
  check(
    "year_descending",
    (await select(page, "Ordre").inputValue()) === "desc" &&
      ys.length > 10 &&
      sorted(ys, (a, b) => a >= b),
    ys.slice(0, 6).join(", "),
  );
  check(
    "sort_keeps_total",
    (await countOf(page)) === total && (await resetButton(page).isVisible()),
  );
  const orderLabels = await select(page, "Ordre")
    .locator("option")
    .allInnerTexts();
  check(
    "direction_labels",
    orderLabels.join("/") === "Plus anciennes/Plus récentes",
    orderLabels.join("/"),
  );
  await select(page, "Ordre").selectOption("asc");
  await settled(page);
  ys = await years(page);
  check(
    "year_ascending",
    ys.length > 10 && sorted(ys, (a, b) => a <= b),
    ys.slice(0, 6).join(", "),
  );
  await shot(page, "year-ascending");

  // Watched filter with no match on the test account (nothing watched).
  const watchOptions = await select(page, "Visionnage")
    .locator("option")
    .allInnerTexts();
  check(
    "film_watch_choices",
    watchOptions.join("/") === "Tous/Non vus/Vus/En cours",
    watchOptions.join("/"),
  );
  await select(page, "Visionnage").selectOption("played");
  await settled(page);
  const playedCount = await countOf(page);
  const noMatch = page.getByText("Aucun film ne correspond à ces filtres");
  if (playedCount === 0) {
    check("no_match_state", await noMatch.isVisible(), await countText(page));
    await shot(page, "no-match");
    await page
      .getByRole("button", { name: "Réinitialiser les filtres" })
      .click();
    await settled(page);
    check(
      "reset_filters_keeps_sort",
      (await select(page, "Visionnage").inputValue()) === "all" &&
        (await select(page, "Trier par").inputValue()) === "year" &&
        (await select(page, "Ordre").inputValue()) === "asc" &&
        (await countOf(page)) === total,
    );
    check(
      "reset_focuses_sort",
      await page.evaluate(
        () =>
          document.activeElement
            ?.closest("label")
            ?.textContent?.trim()
            .startsWith("Trier par") ?? false,
      ),
    );
  } else {
    // Someone watched films on the test account: check the filter instead.
    check(
      "played_filter",
      playedCount < total &&
        (await cards(page).locator(".badge.played").count()) ===
          (await cards(page).count()),
      `${playedCount}`,
    );
    await select(page, "Visionnage").selectOption("all");
    await settled(page);
  }
  await select(page, "Visionnage").selectOption("unplayed");
  await settled(page);
  const unplayed = await countOf(page);
  check(
    "unplayed_plus_played_is_total",
    unplayed + playedCount === total,
    `${unplayed} + ${playedCount} / ${total}`,
  );
  await select(page, "Visionnage").selectOption("all");
  await settled(page);

  // Genre.
  await page.waitForFunction(
    () =>
      document
        .querySelector('.toolbar select[aria-busy="false"]')
        ?.closest("label")
        ?.textContent?.includes("Genre"),
    null,
    { timeout: 20000 },
  );
  const genreOptions = await select(page, "Genre")
    .locator("option")
    .allInnerTexts();
  const genre = genreOptions.includes("Action") ? "Action" : genreOptions[1];
  await select(page, "Genre").selectOption(genre);
  await settled(page);
  const genreCount = await countOf(page);
  check(
    "genre_narrows",
    genreOptions[0] === "Tous les genres" &&
      genreOptions.length > 2 &&
      genreCount > 0 &&
      genreCount < total,
    `${genre} : ${genreCount} / ${total} (${genreOptions.length - 1} genres)`,
  );

  // Favorites, combined with the genre.
  await favorites(page).click();
  await settled(page);
  const favCount = await countOf(page);
  check(
    "favorites_toggle",
    (await favorites(page).getAttribute("aria-pressed")) === "true" &&
      favCount <= genreCount,
    `${favCount}`,
  );
  await shot(page, "genre-favorites");
  await favorites(page).click();
  await settled(page);
  check(
    "favorites_off",
    (await favorites(page).getAttribute("aria-pressed")) === "false" &&
      (await countOf(page)) === genreCount,
  );

  // Another library has its own choices, and no "En cours" for series.
  await openLibrary(page, series);
  const seriesWatch = await select(page, "Visionnage")
    .locator("option")
    .allInnerTexts();
  check(
    "series_defaults_and_choices",
    (await select(page, "Trier par").inputValue()) === "name" &&
      (await select(page, "Genre").inputValue()) === "" &&
      seriesWatch.join("/") === "Tous/Non vus/Vus",
    seriesWatch.join("/"),
  );
  await openLibrary(page, films);
  const kept = [
    await select(page, "Trier par").inputValue(),
    await select(page, "Ordre").inputValue(),
    await select(page, "Genre").inputValue(),
    await countOf(page),
  ];
  check(
    "film_choices_kept",
    (await select(page, "Trier par").inputValue()) === "year" &&
      (await select(page, "Ordre").inputValue()) === "asc" &&
      (await select(page, "Genre").inputValue()) === genre &&
      (await countOf(page)) === genreCount,
    kept.join(" / "),
  );

  // Across a restart.
  check("first_exit_code", (await app.close()) === 0);
  app = await launch();
  page = app.page;
  await libraryNav(page).waitFor({ timeout: 30000 });
  await openLibrary(page, films);
  check(
    "choices_kept_after_restart",
    (await select(page, "Trier par").inputValue()) === "year" &&
      (await select(page, "Genre").inputValue()) === genre &&
      (await countOf(page)) === genreCount,
  );

  // Quick successive changes: only the last one shows.
  await select(page, "Genre").selectOption("");
  await select(page, "Trier par").selectOption("rating");
  await select(page, "Trier par").selectOption("dateAdded");
  await select(page, "Trier par").selectOption("name");
  await settled(page);
  const quick = await titles(page);
  check(
    "last_change_wins",
    (await countOf(page)) === total &&
      (await select(page, "Ordre").inputValue()) === "asc" &&
      quick.join("\n") === t.join("\n"),
    quick.slice(0, 3).join(" | "),
  );

  // Keyboard: a focused select changes with the arrows.
  await select(page, "Trier par").focus();
  await page.keyboard.press("ArrowDown");
  await settled(page);
  check(
    "keyboard_changes_sort",
    (await select(page, "Trier par").inputValue()) === "dateAdded" &&
      (await select(page, "Ordre").inputValue()) === "desc",
    await select(page, "Trier par").inputValue(),
  );
  await shot(page, "keyboard");

  // Scroll a few pages in: the infinite list keeps the options.
  await page.locator("main.content").evaluate((el) => {
    el.scrollTop = el.scrollHeight;
  });
  await page.waitForFunction(
    () => document.querySelectorAll("ul.grid button.card").length > 60,
    null,
    { timeout: 20000 },
  );
  check(
    "next_page_same_order",
    new Set(await titles(page)).size === (await cards(page).count()),
    `${await cards(page).count()} cartes`,
  );

  // Cleanup: defaults, nothing stored.
  await resetButton(page).click();
  await settled(page);
  check(
    "cleanup_defaults",
    !(await resetButton(page).isVisible()) &&
      (await page.evaluate(
        () =>
          Object.keys(localStorage).filter((k) =>
            k.startsWith("viewer.library-options."),
          ).length,
      )) === 0,
  );

  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  finish();
}
