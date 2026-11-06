// Gate 3 favorites (ADR 0014) on the real Emby test account, driven over CDP. The
// app signs in through the local proxy (net-proxy.mjs) so a failed change can be
// simulated without touching the server.
// Read only by default:
// - the home row "Favoris" matches `emby_favorites` (hidden when empty);
// - film and series detail pages show a "Favori" toggle with the server state;
// - Server cut while toggling: the toggle goes back, "Favori non modifié" is shown,
//   nothing reaches the server; back online, the page shows the server state.
// With VIEWER_E2E_FAVORITE_WRITES=1 (writes one favorite, then clears it):
// - adding shows in the home row, survives a restart, opens from the row;
// - Space on the toggle removes it, the row follows; the server list ends as before.
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  signIn,
  signOut,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
if (!account.url || /^http:\/\/(127\.0\.0\.1|localhost)/.test(account.url))
  throw new Error(
    `VIEWER_EMBY_URL must be the server address, got ${account.url}`,
  );
const writes = process.env.VIEWER_E2E_FAVORITE_WRITES === "1";
const { check, shot, finish } = createReport("gate3-favorites");
const proxy = await startProxy(account.url);
console.log(
  `proxy ${proxy.url} → server; écritures : ${writes ? "oui" : "non"}`,
);

const invoke = (page, cmd, args = {}) =>
  page.evaluate(
    ([c, a]) => window.__TAURI_INTERNALS__.invoke(c, a),
    [cmd, args],
  );
const serverFavorites = async (page) =>
  (await invoke(page, "emby_favorites")).map((i) => i.id);

const row = (page, title) =>
  page.locator("section.row", {
    has: page.locator("h2", { hasText: new RegExp(`^${title}$`) }),
  });
const favoritesRow = (page) => row(page, "Favoris");
const rowIds = (locator) =>
  locator
    .locator("button.card")
    .evaluateAll((els) => els.map((e) => e.getAttribute("data-item-id")));
// CSS rather than role: the page stays reachable under the "injoignable" overlay
// (inert), where the toggle must be read.
const toggle = (page) => page.locator(".detail button.favorite");
/** Title text, or the alt text of the logo that replaces it. */
const titleText = (page) =>
  detailTitle(page).evaluate(
    (h) => h.textContent.trim() || h.querySelector("img")?.alt || "",
  );
const pressed = async (page) =>
  (await toggle(page).getAttribute("aria-pressed")) === "true";
const lostTitle = (page) => page.locator("#lost-title");

async function homeLoaded(page) {
  await homeHeading(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () => !document.querySelector(".home .skeleton"),
    null,
    { timeout: 20000 },
  );
}

async function appUsable(page, timeout = 40000) {
  await page.waitForFunction(
    () => {
      const nav = document.querySelector('nav[aria-label="Bibliothèques"]');
      return (
        !!nav &&
        !nav.closest("[inert]") &&
        !document.querySelector("#lost-title")
      );
    },
    null,
    { timeout },
  );
}

/** Opens a card and waits for the detail page and its toggle. */
async function openDetail(page, card) {
  await card.click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await toggle(page).waitFor({ timeout: 20000 });
}

async function closeDetail(page) {
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
}

/** The toggle has its server answer (no change in flight). */
const settledToggle = (page) =>
  page
    .locator('.detail button.favorite[aria-disabled="false"]')
    .waitFor({ timeout: 20000 });

let app = await launch();
try {
  let page = app.page;
  // Start signed out, then sign in through the proxy.
  await Promise.race([
    page.locator("#signin-title").waitFor({ timeout: 40000 }),
    libraryNav(page).waitFor({ timeout: 40000 }),
    lostTitle(page).waitFor({ timeout: 40000 }),
  ]);
  if (await lostTitle(page).count()) {
    // Left by an interrupted run (proxy gone).
    await page.getByRole("button", { name: "Changer de serveur" }).click();
    await page.locator("#server-url").waitFor({ timeout: 10000 });
  } else if (await libraryNav(page).count()) {
    await signOut(page);
  }
  await signIn(page, { url: proxy.url, user: account.user }, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });

  // 1. Home row against the server list.
  await homeLoaded(page);
  const before = await serverFavorites(page);
  const shownBefore = await rowIds(favoritesRow(page));
  check(
    "home_row_matches_server",
    before.length === 0
      ? (await favoritesRow(page).count()) === 0
      : shownBefore.join() === before.join(),
    `${before.length} favori(s) sur le serveur`,
  );
  await shot(page, "home");

  // 2. Film detail: toggle with the server state.
  const libraries = await libraryNav(page)
    .locator("ul.libraries button")
    .evaluateAll((els) =>
      els.map((e) => ({
        kind: e.querySelector(".kind")?.textContent.trim(),
        name: e.lastChild?.textContent?.trim() ?? "",
      })),
    );
  const films = libraries.find((l) => l.kind === "Films")?.name;
  const series = libraries.find((l) => l.kind === "Séries")?.name;
  const filmCard = row(page, `Ajoutés récemment · ${films}`)
    .locator("button.card")
    .first();
  const filmId = await filmCard.getAttribute("data-item-id");
  await openDetail(page, filmCard);
  const filmName = await titleText(page);
  const wasFavorite = before.includes(filmId);
  check(
    "toggle_named_favori",
    (await page
      .locator(".detail")
      .getByRole("button", { name: "Favori", exact: true })
      .count()) === 1,
  );
  check(
    "film_toggle_state",
    (await pressed(page)) === wasFavorite &&
      (await page.locator(".detail button.play").count()) === 1,
    `${filmName} : ${wasFavorite ? "favori" : "pas favori"}`,
  );
  await shot(page, "film-detail");

  // 3. Server cut while toggling: back to the previous state, nothing sent.
  const forwarded = proxy.counts.forwarded;
  await proxy.set("down");
  await toggle(page).click();
  await lostTitle(page).waitFor({ timeout: 25000 });
  await page
    .locator(".detail .play-error", { hasText: "Favori non modifié" })
    .waitFor({ timeout: 10000 });
  check(
    "failed_change_rolls_back",
    (await pressed(page)) === wasFavorite,
    `Serveur coupé pendant le clic`,
  );
  check("failed_change_not_sent", proxy.counts.forwarded === forwarded);
  await shot(page, "failed-change");
  await proxy.set("up");
  await appUsable(page);
  await settledToggle(page);
  check(
    "back_online_server_state",
    (await pressed(page)) === wasFavorite &&
      (await serverFavorites(page)).join() === before.join(),
  );
  await page
    .locator(".detail .play-error", { hasText: "Favori non modifié" })
    .getByRole("button", { name: "Fermer" })
    .click();
  await closeDetail(page);

  // 4. Series detail: a toggle too, no play button.
  await homeLoaded(page);
  const seriesCard = row(page, `Ajoutés récemment · ${series}`)
    .locator("button.card")
    .first();
  const seriesId = await seriesCard.getAttribute("data-item-id");
  await openDetail(page, seriesCard);
  check(
    "series_toggle_state",
    (await pressed(page)) === before.includes(seriesId) &&
      (await page.locator(".detail button.play").count()) === 0,
    await titleText(page),
  );
  await closeDetail(page);

  if (writes && !wasFavorite) {
    // 5. Add: the toggle, then the home row, follow the server.
    await homeLoaded(page);
    await openDetail(
      page,
      row(page, `Ajoutés récemment · ${films}`).locator(
        `button.card[data-item-id="${filmId}"]`,
      ),
    );
    await toggle(page).click();
    check("add_is_immediate", await pressed(page));
    await settledToggle(page);
    check(
      "add_confirmed_by_server",
      (await pressed(page)) &&
        (await serverFavorites(page)).includes(filmId) &&
        (await page.locator(".detail .play-error").count()) === 0,
    );
    await shot(page, "added");
    await closeDetail(page);
    await page
      .locator(`section.row button.card[data-item-id="${filmId}"]`)
      .first()
      .waitFor({ timeout: 10000 });
    await page.waitForFunction(
      (id) =>
        [...document.querySelectorAll("section.row")]
          .find((s) => s.querySelector("h2")?.textContent.trim() === "Favoris")
          ?.querySelector(`button.card[data-item-id="${id}"]`),
      filmId,
      { timeout: 10000 },
    );
    check("home_row_shows_added", true, filmName);
    await shot(page, "home-with-favorite");

    // 6. Restart: the server keeps it; the row opens it.
    check("first_exit_code", (await app.close()) === 0);
    app = await launch();
    page = app.page;
    await homeLoaded(page);
    const card = favoritesRow(page).locator(
      `button.card[data-item-id="${filmId}"]`,
    );
    check("kept_after_restart", (await card.count()) === 1);
    await openDetail(page, card);
    await settledToggle(page);
    check("detail_from_row_pressed", await pressed(page));

    // 7. Keyboard removal; the row follows.
    await toggle(page).focus();
    await page.keyboard.press("Space");
    await settledToggle(page);
    check(
      "space_removes",
      !(await pressed(page)) && !(await serverFavorites(page)).includes(filmId),
    );
    await closeDetail(page);
    await page.waitForFunction(
      (id) =>
        ![...document.querySelectorAll("section.row")]
          .find((s) => s.querySelector("h2")?.textContent.trim() === "Favoris")
          ?.querySelector(`button.card[data-item-id="${id}"]`),
      filmId,
      { timeout: 10000 },
    );
    check(
      "home_row_follows_removal",
      before.length > 0 || (await favoritesRow(page).count()) === 0,
    );
    check(
      "server_list_as_before",
      (await serverFavorites(page)).join() === before.join(),
    );
  } else {
    check(
      "writes_skipped",
      true,
      writes ? "le film était déjà favori" : "VIEWER_E2E_FAVORITE_WRITES absent",
    );
  }

  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  await proxy.stop();
  finish();
}
