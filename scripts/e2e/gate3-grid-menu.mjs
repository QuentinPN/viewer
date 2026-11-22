// Compact library grid and card menu on the real Emby test
// account, driven over CDP. Each change it makes is undone before the end:
// - letter rail, icon rail and posters per row of the Films grid;
// - a letter jump focuses a card of that letter;
// - Visionnage cycles back to "Tous", sort and filters panels open, Réinitialiser;
// - the card menu opens by right-click, by the "…" button and by Shift+F10;
// - favourite and played from the menu update the card, then are undone;
// - the playlist page lists the playlists without creating one; ← goes back.
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  libraryNav,
  signIn,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-grid-menu");

const iconRail = (page) =>
  page.locator('.icon-rail[aria-label="Tri et filtres"]');
const letterRail = (page) =>
  page.locator('.letter-rail[aria-label="Aller à une lettre"]');
const resetButton = (page) =>
  iconRail(page).getByRole("button", {
    name: "Réinitialiser le tri et les filtres",
  });
const watchButton = (page) =>
  iconRail(page).getByRole("button", { name: /^Visionnage : / });
const menu = (page) => page.locator('.card-menu-root [role="menu"]');
const menuRow = (page, name) =>
  menu(page).getByRole("menuitem", { name, exact: true });
const countOf = async (page) =>
  Number(
    ((await page.locator(".heading .count").textContent()) ?? "").replace(
      /\D/g,
      "",
    ),
  ) || 0;
const invoke = (page, cmd, args = {}) =>
  page.evaluate(([c, a]) => window.__viewer.invoke(c, a), [cmd, args]);

async function settled(page) {
  await page.waitForFunction(
    () =>
      document.querySelector("main.content")?.getAttribute("aria-busy") ===
        "false" &&
      /\d/.test(document.querySelector(".heading .count")?.textContent ?? "") &&
      !document.querySelector("ul.grid .skeleton"),
    null,
    { timeout: 20000 },
  );
}

/** Waits for the opening fade of the menu panel; returns its final opacity. */
async function menuShown(page) {
  return page.locator(".card-menu-root .panel").evaluate(async (p) => {
    await Promise.all(p.getAnimations().map((a) => a.finished));
    return getComputedStyle(p).opacity;
  });
}

async function closeMenu(page) {
  await page.keyboard.press("Escape");
  await menu(page).waitFor({ state: "detached", timeout: 5000 });
}

/** Menu row labels of the open menu. */
const rowLabels = (page) =>
  menu(page)
    .getByRole("menuitem")
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));

let app = await launch();
try {
  const { page } = app;
  if ((await startScreen(page)) === "signedOut")
    await signIn(page, account, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });
  const films = await libraryNav(page)
    .locator("ul.libraries button")
    .evaluateAll(
      (els) =>
        els
          .find((e) => e.querySelector(".kind")?.textContent.trim() === "Films")
          ?.lastChild?.textContent?.trim() ?? "",
    );
  check("films_library", films, films);
  await libraryNav(page)
    .locator("ul.libraries button", { hasText: films })
    .first()
    .click();
  await iconRail(page).waitFor({ timeout: 20000 });
  await settled(page);
  if (await resetButton(page).isVisible()) {
    await resetButton(page).click();
    await settled(page);
  }
  const total = await countOf(page);
  check("count_shown", total > 0, `${total}`);

  // Rails and density.
  const letters = await letterRail(page).locator("button.letter").count();
  check("letter_rail", letters === 27, `${letters} lettres`);
  const icons = await iconRail(page).locator("button.rail-icon").count();
  check("icon_rail", icons === 5, `${icons} icônes sans Réinitialiser`);
  const perRow = await page
    .locator("ul.grid")
    .evaluate((g) => getComputedStyle(g).gridTemplateColumns.split(" ").length);
  const width = await page.evaluate(() => innerWidth);
  check("posters_per_row", perRow >= 5, `${perRow} par ligne à ${width} px`);
  await shot(page, "grid");

  // Letter jump.
  const letterL = letterRail(page).locator('button[data-letter="L"]');
  if (await letterL.isEnabled()) {
    await letterL.click();
    await page.waitForFunction(
      () => document.activeElement?.matches("ul.grid button.card"),
      null,
      { timeout: 15000 },
    );
    const focused = await page.evaluate(() =>
      document.activeElement.querySelector(".title")?.textContent.trim(),
    );
    check("letter_jump", /^\S/.test(focused ?? ""), `focus sur « ${focused} »`);
    await shot(page, "letter-L");
  } else check("letter_jump", false, "lettre L indisponible");

  // Visionnage cycle.
  const seen = [];
  for (let i = 0; i < 6; i++) {
    await watchButton(page).click();
    await settled(page);
    const label = await watchButton(page).getAttribute("aria-label");
    seen.push(`${label.replace("Visionnage : ", "")} (${await countOf(page)})`);
    if (label === "Visionnage : Tous") break;
  }
  check(
    "watch_cycle",
    seen.at(-1)?.startsWith("Tous") && seen.length >= 3,
    seen.join(" → "),
  );
  check("watch_back_total", (await countOf(page)) === total);

  // Sort panel, then Réinitialiser.
  await iconRail(page).getByRole("button", { name: /^Tri/ }).click();
  const sortPanel = page.locator("#sort-panel");
  check(
    "sort_panel_open",
    await sortPanel.evaluate((p) => p.matches(":popover-open")),
  );
  await shot(page, "sort-panel");
  const radios = sortPanel.locator('input[name="grid-sort"]');
  const sortKeys = await radios.evaluateAll((els) => els.map((e) => e.value));
  const other = sortKeys.find((k) => k !== sortKeys[0]);
  await sortPanel.locator(`input[name="grid-sort"][value="${other}"]`).check();
  await settled(page);
  await page.keyboard.press("Escape");
  check("reset_shown", await resetButton(page).isVisible(), `tri ${other}`);
  const sortTop = await iconRail(page)
    .locator("button.rail-icon")
    .first()
    .boundingBox();
  await resetButton(page).click();
  await settled(page);
  check("reset_hidden", !(await resetButton(page).isVisible()));
  const sortTopAfter = await iconRail(page)
    .locator("button.rail-icon")
    .first()
    .boundingBox();
  check(
    "rail_steady",
    Math.abs((sortTop?.y ?? 0) - (sortTopAfter?.y ?? -99)) < 1,
    `${sortTop?.y} → ${sortTopAfter?.y}`,
  );

  // Filters panel.
  await iconRail(page)
    .getByRole("button", { name: /^Filtres/ })
    .click();
  const filtersPanel = page.locator("#filters-panel");
  check(
    "filters_panel_open",
    await filtersPanel.evaluate((p) => p.matches(":popover-open")),
  );
  await shot(page, "filters-panel");
  await page.keyboard.press("Escape");

  // Card menu: a film neither played nor favourite, from the top of the grid.
  await page.locator("main.content").evaluate((m) => m.scrollTo(0, 0));
  const ids = await cards(page).evaluateAll((els) =>
    els.slice(0, 30).map((e) => e.getAttribute("data-item-id")),
  );
  let targetId = null;
  for (const id of ids) {
    const card = page.locator(`ul.grid button.card[data-item-id="${id}"]`);
    if (await card.locator(".badge.played").count()) continue;
    await card.click({ button: "right" });
    await menu(page).waitFor({ timeout: 5000 });
    const labels = await rowLabels(page);
    await closeMenu(page);
    if (labels.includes("Ajouter aux favoris")) {
      targetId = id;
      break;
    }
  }
  check("menu_target", targetId, targetId ?? "aucun film libre");
  const card = page.locator(`ul.grid button.card[data-item-id="${targetId}"]`);
  const holder = page.locator(".menu-holder", {
    has: page.locator(`button.card[data-item-id="${targetId}"]`),
  });

  await card.click({ button: "right" });
  await menu(page).waitFor({ timeout: 5000 });
  const labels = await rowLabels(page);
  check(
    "menu_rows",
    labels.join("|") ===
      "Lire|Ajouter à une playlist|Marquer comme vu|Ajouter aux favoris",
    labels.join(" | "),
  );
  check(
    "menu_focus",
    await page.evaluate(
      () => document.activeElement?.getAttribute("role") === "menuitem",
    ),
  );
  check("menu_opaque", (await menuShown(page)) === "1");
  await shot(page, "menu-right-click");
  await closeMenu(page);
  check(
    "focus_back_on_card",
    await card.evaluate((c) => document.activeElement === c),
  );

  await card.press("Shift+F10");
  check(
    "menu_shift_f10",
    await menu(page)
      .waitFor({ timeout: 5000 })
      .then(
        () => true,
        () => false,
      ),
  );
  await closeMenu(page);

  // "…" button: shown on hover.
  await card.hover();
  await holder.locator("button.more").click();
  await menu(page).waitFor({ timeout: 5000 });
  check("menu_more_button", true);

  // Playlist page, read only.
  await menuRow(page, "Ajouter à une playlist").click();
  await menu(page)
    .getByRole("menuitem", { name: "Nouvelle playlist…" })
    .waitFor({ timeout: 10000 });
  await page.waitForFunction(
    () =>
      document
        .querySelector('.card-menu-root [role="menu"]')
        ?.getAttribute("aria-busy") === "false",
    null,
    { timeout: 10000 },
  );
  const playlistText = await menu(page).textContent();
  check(
    "playlist_page",
    /Aucune playlist|Nouvelle playlist/.test(playlistText),
    playlistText.replace(/\s+/g, " ").trim().slice(0, 120),
  );
  await menuShown(page);
  await shot(page, "menu-playlists");
  // Desktop menus: ← goes back one page, Escape closes.
  await page.keyboard.press("ArrowLeft");
  const backOnMain = await menuRow(page, "Ajouter aux favoris")
    .waitFor({ timeout: 3000 })
    .then(
      () => true,
      () => false,
    );
  check("left_back_one_page", backOnMain);
  check(
    "focus_in_menu_after_back",
    await page.evaluate(
      () => document.activeElement?.getAttribute("role") === "menuitem",
    ),
  );
  await closeMenu(page);

  // Favourite, then undo.
  const isFavorite = async () =>
    (await invoke(page, "emby_favorites")).some((i) => i.id === targetId);
  // The menu closes before the server answers (optimistic): poll for 5 s.
  const favoriteBecomes = async (want) => {
    for (let i = 0; i < 25; i++) {
      if ((await isFavorite()) === want) return true;
      await page.waitForTimeout(200);
    }
    return false;
  };
  await card.click({ button: "right" });
  await menuRow(page, "Ajouter aux favoris").click();
  await menu(page).waitFor({ state: "detached", timeout: 10000 });
  check("favorite_added", await favoriteBecomes(true));
  await card.click({ button: "right" });
  check(
    "menu_follows_favorite",
    (await rowLabels(page)).includes("Retirer des favoris"),
  );
  await menuRow(page, "Retirer des favoris").click();
  await menu(page).waitFor({ state: "detached", timeout: 10000 });
  check("favorite_removed", await favoriteBecomes(false));

  // Played, then undo.
  await card.click({ button: "right" });
  await menuRow(page, "Marquer comme vu").click();
  await card
    .locator(".badge.played")
    .waitFor({ timeout: 10000 })
    .catch(() => {});
  check("played_badge", (await card.locator(".badge.played").count()) === 1);
  await shot(page, "played");
  await card.click({ button: "right" });
  await menuRow(page, "Marquer comme non vu").click();
  await card
    .locator(".badge.played")
    .waitFor({ state: "detached", timeout: 10000 })
    .catch(() => {});
  check("played_undone", (await card.locator(".badge.played").count()) === 0);

  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (e) {
  check("scenario", false, e.stack ?? e);
} finally {
  if (app) await app.close().catch(() => {});
  finish();
}
