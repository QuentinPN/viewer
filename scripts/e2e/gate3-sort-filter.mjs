// Gate 3 sort and filters of a library grid, on the
// real Emby test account, driven over CDP. Read only: nothing is written to the server.
// - defaults: title A→Z, nothing filtered, no "Réinitialiser";
// - each sort orders the grid as the server does (years checked on the cards);
// - a filter with no match shows its own empty state, whose button keeps the sort;
// - a genre narrows the count; favorites toggle (aria-pressed);
// - "En cours" for films only; choices kept per library and across a restart;
// - quick successive changes: the grid matches the last choice only;
// - the keyboard changes the sort in its panel; cleanup leaves the defaults.
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

const rail = (page) => page.locator('.icon-rail[aria-label="Tri et filtres"]');
const sortIcon = (page) => rail(page).getByRole("button", { name: /^Tri : / });
const filtersIcon = (page) =>
  rail(page).getByRole("button", { name: /^Filtres : / });
const watchIcon = (page) =>
  rail(page).getByRole("button", { name: /^Visionnage : / });
const favorites = (page) =>
  rail(page).getByRole("button", { name: "Favoris seulement" });
const resetButton = (page) =>
  rail(page).getByRole("button", {
    name: "Réinitialiser le tri et les filtres",
  });
const sortPanel = (page) => page.locator("#sort-panel");
const filtersPanel = (page) => page.locator("#filters-panel");
const genreSelect = (page) =>
  filtersPanel(page)
    .locator("label.field", { has: page.locator('span:text-is("Genre")') })
    .locator("select");
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

/** Open state of a rail panel; false outside a library, where it does not exist. */
const isOpen = async (panel) =>
  (await panel.count()) > 0 &&
  (await panel.evaluate((p) => p.matches(":popover-open")));
async function openPanel(page, icon, panel) {
  if (!(await isOpen(panel))) await icon.click();
  await page.waitForFunction(
    (id) => document.getElementById(id)?.matches(":popover-open"),
    await panel.getAttribute("id"),
    { timeout: 5000 },
  );
}
async function closePanels(page) {
  for (const panel of [sortPanel(page), filtersPanel(page)])
    if (await isOpen(panel)) await page.keyboard.press("Escape");
}

/** Sort key and direction, read from the radios of the Tri panel. */
const sortKey = (page) =>
  sortPanel(page)
    .locator('input[name="grid-sort"]:checked')
    .evaluate((i) => i.value);
const descending = (page) =>
  sortPanel(page)
    .locator('input[name="grid-order"]')
    .nth(1)
    .evaluate((i) => i.checked);
const watchState = async (page) =>
  (await watchIcon(page).getAttribute("aria-label")).replace(
    "Visionnage : ",
    "",
  );
const genreValue = (page) => genreSelect(page).evaluate((s) => s.value);

async function setSort(page, key) {
  await openPanel(page, sortIcon(page), sortPanel(page));
  await sortPanel(page)
    .locator(`input[name="grid-sort"][value="${key}"]`)
    .check();
  await settled(page);
}
async function setDescending(page, desc) {
  await openPanel(page, sortIcon(page), sortPanel(page));
  await sortPanel(page)
    .locator('input[name="grid-order"]')
    .nth(desc ? 1 : 0)
    .check();
  await settled(page);
}
/** Clicks Visionnage until it shows `label`; returns the labels seen. */
async function setWatch(page, label) {
  const seen = [await watchState(page)];
  for (let i = 0; i < 5 && seen.at(-1) !== label; i++) {
    await watchIcon(page).click();
    await settled(page);
    seen.push(await watchState(page));
  }
  if (seen.at(-1) !== label) throw new Error(`Visionnage : ${seen.join("/")}`);
  return seen;
}
async function setGenre(page, value) {
  await openPanel(page, filtersIcon(page), filtersPanel(page));
  await genreSelect(page).selectOption(value);
  await settled(page);
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
  await closePanels(page);
  await libraryNav(page)
    .locator("ul.libraries button", { hasText: name })
    .first()
    .click();
  await page
    .locator(".heading h1", { hasText: new RegExp(`^${name}$`) })
    .waitFor({ timeout: 20000 });
  await rail(page).waitFor({ timeout: 20000 });
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
    (await sortKey(page)) === "name" &&
      !(await descending(page)) &&
      (await watchState(page)) === "Tous" &&
      (await genreValue(page)) === "" &&
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
  await setSort(page, "year");
  let ys = await years(page);
  check(
    "year_descending",
    (await descending(page)) && ys.length > 10 && sorted(ys, (a, b) => a >= b),
    ys.slice(0, 6).join(", "),
  );
  check(
    "sort_keeps_total",
    (await countOf(page)) === total && (await resetButton(page).isVisible()),
  );
  const orderLabels = await sortPanel(page)
    .locator("label.choice", { has: page.locator('input[name="grid-order"]') })
    .allInnerTexts();
  check(
    "direction_labels",
    orderLabels.map((l) => l.trim()).join("/") ===
      "Plus anciennes/Plus récentes",
    orderLabels.join("/"),
  );
  check(
    "sort_icon_label",
    (await sortIcon(page).getAttribute("aria-label")) ===
      "Tri : Année, Plus récentes",
    await sortIcon(page).getAttribute("aria-label"),
  );
  await setDescending(page, false);
  ys = await years(page);
  check(
    "year_ascending",
    ys.length > 10 && sorted(ys, (a, b) => a <= b),
    ys.slice(0, 6).join(", "),
  );
  await shot(page, "year-ascending");
  await closePanels(page);

  // Visionnage cycle, then the played filter.
  const cycle = await setWatch(page, "En cours");
  cycle.push(...(await setWatch(page, "Tous")).slice(1));
  check(
    "film_watch_choices",
    cycle.join("/") === "Tous/Non vus/Vus/En cours/Tous",
    cycle.join("/"),
  );
  await setWatch(page, "Vus");
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
      (await watchState(page)) === "Tous" &&
        (await sortKey(page)) === "year" &&
        !(await descending(page)) &&
        (await countOf(page)) === total,
    );
    check(
      "reset_focuses_sort",
      await page.evaluate(
        () =>
          document.activeElement
            ?.getAttribute("aria-label")
            ?.startsWith("Tri : ") ?? false,
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
    await setWatch(page, "Tous");
  }
  await setWatch(page, "Non vus");
  const unplayed = await countOf(page);
  check(
    "unplayed_plus_played_is_total",
    unplayed + playedCount === total,
    `${unplayed} + ${playedCount} / ${total}`,
  );
  await setWatch(page, "Tous");

  // Genre, in the Filtres panel.
  await openPanel(page, filtersIcon(page), filtersPanel(page));
  await page.waitForFunction(
    () =>
      document
        .querySelector("#filters-panel select")
        ?.getAttribute("aria-busy") === "false",
    null,
    { timeout: 20000 },
  );
  const genreOptions = await genreSelect(page)
    .locator("option")
    .allInnerTexts();
  const genre = genreOptions.includes("Action") ? "Action" : genreOptions[1];
  await setGenre(page, genre);
  const genreCount = await countOf(page);
  check(
    "genre_narrows",
    genreOptions[0] === "Tous les genres" &&
      genreOptions.length > 2 &&
      genreCount > 0 &&
      genreCount < total,
    `${genre} : ${genreCount} / ${total} (${genreOptions.length - 1} genres)`,
  );
  await closePanels(page);
  check(
    "filters_icon_label",
    (await filtersIcon(page).getAttribute("aria-label")) ===
      `Filtres : ${genre}`,
    await filtersIcon(page).getAttribute("aria-label"),
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
  const seriesDefaults =
    (await sortKey(page)) === "name" && (await genreValue(page)) === "";
  const seriesWatch = await setWatch(page, "Vus");
  seriesWatch.push(...(await setWatch(page, "Tous")).slice(1));
  check(
    "series_defaults_and_choices",
    seriesDefaults && seriesWatch.join("/") === "Tous/Non vus/Vus/Tous",
    seriesWatch.join("/"),
  );
  await openLibrary(page, films);
  const kept = [
    await sortKey(page),
    await descending(page),
    await genreValue(page),
    await countOf(page),
  ];
  check(
    "film_choices_kept",
    kept[0] === "year" &&
      kept[1] === false &&
      kept[2] === genre &&
      kept[3] === genreCount,
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
    (await sortKey(page)) === "year" &&
      (await genreValue(page)) === genre &&
      (await countOf(page)) === genreCount,
  );

  // Quick successive changes: only the last one shows.
  await setGenre(page, "");
  await closePanels(page);
  await openPanel(page, sortIcon(page), sortPanel(page));
  for (const key of ["rating", "dateAdded", "name"])
    await sortPanel(page)
      .locator(`input[name="grid-sort"][value="${key}"]`)
      .check();
  await settled(page);
  await page.waitForTimeout(500);
  await settled(page);
  const quick = await titles(page);
  check(
    "last_change_wins",
    (await countOf(page)) === total &&
      !(await descending(page)) &&
      quick.join("\n") === t.join("\n"),
    quick.slice(0, 3).join(" | "),
  );

  // Keyboard: in the open Tri panel, ↓ moves to the next sort.
  await sortPanel(page).locator('input[name="grid-sort"]:checked').focus();
  await page.keyboard.press("ArrowDown");
  await settled(page);
  check(
    "keyboard_changes_sort",
    (await sortKey(page)) === "dateAdded" && (await descending(page)),
    await sortKey(page),
  );
  await shot(page, "keyboard");
  await page.keyboard.press("Escape");
  check(
    "escape_closes_panel",
    !(await isOpen(sortPanel(page))) &&
      (await page.evaluate(
        () =>
          document.activeElement
            ?.getAttribute("aria-label")
            ?.startsWith("Tri : ") ?? false,
      )),
  );

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
