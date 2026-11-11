// Gate 2 UI scenario on the real Emby server, driven over CDP (see app.mjs): wrong
// password, sign-in, restore after restart, library switch by keyboard, grid paging,
// series and film detail pages, Escape back with focus and scroll kept, narrow
// window, sign-out. Asserts each step and writes screenshots and report.json to
// artifacts/e2e/gate2-ui-<run>. Run it through run.ps1, which reads the test account
// from the Windows Credential Manager; the password never reaches the app's
// environment, the command line or the report.
import { spawnSync } from "node:child_process";
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
const { password } = account;
const { check, shot, finish } = createReport("gate2-ui");

const focusedItemId = (page) =>
  page.evaluate(
    () => document.activeElement?.getAttribute("data-item-id") ?? null,
  );

let app = await launch();
try {
  let page = app.page;
  if ((await startScreen(page)) === "signedIn") await signOut(page);
  await shot(page, "signed-out");

  // Wrong password: an error, no session.
  await signIn(page, account, `${password}-wrong`);
  const alert = page.getByRole("alert");
  await alert.waitFor({ timeout: 15000 });
  check(
    "wrong_password_error",
    (await alert.textContent()).trim().length > 0,
    await alert.textContent(),
  );
  await shot(page, "wrong-password");

  await page.locator("#password").fill(password);
  await page.getByRole("button", { name: "Se connecter", exact: true }).click();
  await libraryNav(page).waitFor({ timeout: 20000 });
  let libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const libraryCount = await libraries.count();
  check("signed_in_libraries", libraryCount > 0, `${libraryCount} libraries`);
  await homeHeading(page).waitFor({ timeout: 20000 });
  await shot(page, "signed-in");

  // Restart: the session comes back from the credential store.
  check("exit_after_sign_in", (await app.close()) === 0);
  app = await launch();
  page = app.page;
  libraries = libraryNav(page).locator("ul.libraries button");
  check("restored_after_restart", (await startScreen(page)) === "signedIn");
  await homeHeading(page).waitFor({ timeout: 20000 });

  // Library switch by keyboard, then grid paging by mouse wheel.
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  const seriesIndex = kinds.indexOf("Séries");
  const filmIndex = kinds.indexOf("Films");
  check("library_kinds", seriesIndex >= 0 && filmIndex >= 0, kinds.join(", "));
  const otherIndex = libraryCount > 1 ? 1 : 0;
  await libraries.nth(otherIndex).focus();
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    (i) =>
      document
        .querySelectorAll("nav ul.libraries button")
        [i]?.getAttribute("aria-current") === "page",
    otherIndex,
  );
  await cards(page).first().waitFor({ timeout: 20000 });
  check("keyboard_library_switch", true, `library ${otherIndex + 1}`);

  const firstPage = await cards(page).count();
  const total = await page.locator(".heading .count").textContent();
  await page.locator("main.content").hover();
  for (let i = 0; i < 12 && (await cards(page).count()) <= firstPage; i++) {
    await page.mouse.wheel(0, 2500);
    await page.waitForTimeout(400);
  }
  const afterScroll = await cards(page).count();
  check(
    "grid_next_page",
    afterScroll > firstPage || firstPage < 60,
    `${firstPage} → ${afterScroll} cards (${total})`,
  );
  await shot(page, "grid-scrolled");

  // Series: open by keyboard, focus on the title, switch season, Escape back to the card.
  await libraries.nth(seriesIndex).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  let seasonsChecked = false;
  for (let n = 0; n < Math.min(6, await cards(page).count()); n++) {
    const card = cards(page).nth(n);
    const id = await card.getAttribute("data-item-id");
    await card.focus();
    await page.keyboard.press("Enter");
    await detailTitle(page).waitFor({ timeout: 15000 });
    await page
      .locator("ol.list li.episode:not(.skeleton)")
      .first()
      .waitFor({ timeout: 20000 });
    if (n === 0) {
      check(
        "detail_title_focused",
        await page.evaluate(
          () => document.activeElement?.id === "detail-title",
        ),
      );
      await shot(page, "series-detail");
    }
    const seasonButtons = page
      .getByRole("group", { name: "Saisons" })
      .getByRole("button");
    if ((await seasonButtons.count()) > 1) {
      const firstEpisode = await page
        .locator("ol.list li.episode")
        .first()
        .textContent();
      await seasonButtons.nth(1).click();
      await page.waitForFunction(
        () =>
          document
            .querySelectorAll(".seasons button")[1]
            ?.getAttribute("aria-pressed") === "true",
      );
      await page
        .waitForFunction(
          (before) => {
            const li = document.querySelector(
              "ol.list li.episode:not(.skeleton)",
            );
            return (
              li &&
              li.textContent !== before &&
              document.querySelector("ol.list")?.getAttribute("aria-busy") !==
                "true"
            );
          },
          firstEpisode,
          { timeout: 15000 },
        )
        .catch(() => {});
      const switched =
        (await page.locator("ol.list li.episode").first().textContent()) !==
        firstEpisode;
      check("season_switch", switched, `card ${n + 1}`);
      await page.locator(".seasons").scrollIntoViewIfNeeded();
      await shot(page, "second-season");
      seasonsChecked = true;
    }
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
    if (n === 0)
      check(
        "escape_focus_back_to_card",
        (await focusedItemId(page)) === id,
        id,
      );
    if (seasonsChecked) break;
  }
  if (!seasonsChecked)
    check(
      "season_switch",
      false,
      "no series with several seasons in the first cards",
    );

  // Films: scroll, open with the mouse, Escape keeps the scroll position.
  await libraries.nth(filmIndex).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await page.locator("main.content").hover();
  await page.mouse.wheel(0, 1500);
  await page.waitForTimeout(800);
  const scrolled = await page
    .locator("main.content")
    .evaluate((e) => e.scrollTop);
  // A card already fully on screen: click() scrolls its target into view first,
  // which would move the grid before the detail opens.
  const visibleCard = await page.evaluate(() => {
    const view = document.querySelector("main.content").getBoundingClientRect();
    return [...document.querySelectorAll("ul.grid button.card")].findIndex(
      (c) => {
        const r = c.getBoundingClientRect();
        return r.top >= view.top && r.bottom <= view.bottom;
      },
    );
  });
  const film = page.locator("ul.grid button.card").nth(visibleCard);
  const filmId = await film.getAttribute("data-item-id");
  const openFilm = async () => {
    await film.click();
    await detailTitle(page).waitFor({ timeout: 15000 });
    await page.waitForFunction(
      () =>
        document.querySelector(".detail")?.getAttribute("aria-busy") !==
          "true" &&
        [...document.querySelectorAll(".detail img")].every((i) => i.complete),
      null,
      { timeout: 15000 },
    );
  };
  await openFilm();
  await shot(page, "film-detail");

  // Back without resizing: the exact scroll offset is kept.
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  const kept = await page.locator("main.content").evaluate((e) => e.scrollTop);
  check(
    "scroll_kept_after_detail",
    scrolled > 0 && Math.abs(kept - scrolled) < 2,
    `${scrolled} → ${kept}`,
  );
  await openFilm();

  // Narrow real window: the layout must not overflow horizontally.
  resizeWindow(app.proc.pid, 700, 700);
  await page.waitForFunction(() => window.innerWidth < 720);
  await page.mouse.wheel(0, 3000);
  await page.waitForTimeout(500);
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
  check(
    "narrow_no_horizontal_overflow",
    overflow <= 0,
    `overflow ${overflow}px at ${await page.evaluate(() => innerWidth)}px`,
  );
  await shot(page, "film-narrow");
  resizeWindow(app.proc.pid, 1280, 800);
  await page.waitForFunction(() => window.innerWidth > 1000);

  // The grid reflowed while narrow, so its offset changed with scroll anchoring:
  // what must hold is that the opened card is focused and on screen again.
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  const visible = await page.evaluate((id) => {
    const card = document.querySelector(`button.card[data-item-id="${id}"]`);
    const view = document
      .querySelector("main.content")
      ?.getBoundingClientRect();
    const r = card?.getBoundingClientRect();
    return (
      document.activeElement === card &&
      !!r &&
      !!view &&
      r.bottom > view.top &&
      r.top < view.bottom
    );
  }, filmId);
  check("card_back_in_view_after_resize", visible, filmId);
  await shot(page, "grid-after-resize");

  await signOut(page);
  await shot(page, "signed-out-end");
  check("exit_after_sign_out", (await app.close()) === 0);
  app = null;

  const entries = spawnSync("cmdkey", ["/list"], {
    encoding: "utf8",
  }).stdout.split(/\r?\n/);
  check(
    "no_app_credential_left",
    !entries.some((l) => /target=.*\.Viewer-debug\s*$/i.test(l)),
  );
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
