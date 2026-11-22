// Redesigned screens at the CSS sizes Windows display scaling gives,
// on the real Emby test account, driven over CDP. Windows scaling shrinks the CSS
// viewport and raises devicePixelRatio; the scenario reproduces both without
// touching the system setting: the real window is resized to the CSS size and
// CDP sets the device scale factor (so srcset picks the images it would pick).
// Layout equivalent only: font rasterization and the native frame are not the
// ones of a real 125 % or 150 % screen.
// For each size: home, film library, film detail, series detail, settings, the
// player with its subtitles panel, the connection-lost overlay, then the sign-in
// screen after signing out. No horizontal page overflow allowed.
// The app signs in through the local proxy `net-proxy.mjs`, which cuts the
// network for the overlay without touching the server. Starts one short
// playback per size, rewound to 0 before stopping (no resume point is kept).
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  playerBadge,
  setScaledSize,
  signIn,
  signInHeading,
  signOut,
  startScreen,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
if (!account.url || /^http:\/\/(127\.0\.0\.1|localhost)/.test(account.url))
  throw new Error(
    `VIEWER_EMBY_URL must be the server address, got ${account.url}`,
  );
const { check, shot, finish } = createReport("gate3-scaling");
const proxy = await startProxy(account.url);

const invoke = (page, cmd, args) =>
  page.evaluate(
    ([c, a]) => window.__TAURI_INTERNALS__.invoke(c, a),
    [cmd, args],
  );

// A 1280 × 720 window at 125 % and 150 %, and a 1920 × 1080 screen at 150 %
// (same CSS size as 1280 × 720 at 100 %, sharper).
const SIZES = [
  { name: "125", width: 1024, height: 576, scale: 1.25 },
  { name: "150", width: 853, height: 480, scale: 1.5 },
  { name: "1080p150", width: 1280, height: 720, scale: 1.5 },
];

const libraryButton = (page, kind) =>
  libraryNav(page).locator("ul.libraries button", { hasText: kind }).first();

async function settled(page) {
  await page.waitForFunction(
    () =>
      document.querySelector("[aria-busy='true']") === null &&
      // Lazy images out of view never load: only the visible ones count.
      [...document.querySelectorAll("img")]
        .filter((i) => {
          const r = i.getBoundingClientRect();
          return (
            r.bottom > 0 &&
            r.top < innerHeight &&
            r.right > 0 &&
            r.left < innerWidth
          );
        })
        .every((i) => i.complete),
    null,
    { timeout: 20000 },
  );
  await page.waitForTimeout(400);
}

async function noOverflow(page, label) {
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
  check(`${label}_no_horizontal_overflow`, overflow <= 0, `${overflow}px`);
}

async function capture(page, label) {
  await settled(page);
  await noOverflow(page, label);
  await shot(page, label);
}

/** Opens the first card of a library and waits for its detail page. */
async function openFirst(page, kind) {
  await libraryButton(page, kind).click();
  const card = page.locator("ul.grid button.card").first();
  await card.waitFor({ timeout: 20000 });
  await settled(page);
  return async () => {
    await card.click();
    await detailTitle(page).waitFor({ timeout: 15000 });
  };
}

/** Index of a film card without resume point among the first ten. */
async function filmWithoutResume(page) {
  const cards = page.locator("ul.grid button.card");
  for (let n = 0; n < Math.min(10, await cards.count()); n++) {
    const id = await cards.nth(n).getAttribute("data-item-id");
    const detail = await invoke(page, "emby_item_detail", { itemId: id });
    if (detail.resume === null) return n;
  }
  throw new Error("no film without resume point");
}

/** Short playback with the controls and the subtitles panel captured. */
async function player(page, label) {
  await libraryButton(page, "Films").click();
  const cards = page.locator("ul.grid button.card");
  await cards.first().waitFor({ timeout: 20000 });
  await cards.nth(await filmWithoutResume(page)).click();
  await detailTitle(page).waitFor({ timeout: 15000 });
  await page.locator(".detail button.play").first().click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 1, 20000);
  check(`${label}_plays`, started?.ok && started.timePos > 1);
  await page.mouse.move(300, 200);
  await page.waitForTimeout(400);
  await noOverflow(page, `${label}-player`);
  await shot(page, `${label}-player`);
  await page.getByRole("button", { name: "Sous-titres" }).click();
  await page.getByRole("dialog", { name: "Sous-titres" }).waitFor();
  await page.waitForTimeout(300);
  const top = await page
    .getByRole("dialog", { name: "Sous-titres" })
    .evaluate((d) => d.getBoundingClientRect().top);
  check(`${label}_panel_below_title_bar`, top >= 32, `top ${top}px`);
  await shot(page, `${label}-player-subtitles`);
  await page.keyboard.press("Escape");
  await page.evaluate(() =>
    window.__TAURI_INTERNALS__.invoke("player_command", {
      command: { type: "seekAbsolute", seconds: 0 },
    }),
  );
  await waitForDiagnostics(page, (d) => (d.timePos ?? 99) < 3);
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
  // The detail page reloads after playback: retry until it closes.
  for (let i = 0; i < 4 && (await detailTitle(page).count()); i++) {
    await settled(page);
    await page.keyboard.press("Escape");
    await page.waitForTimeout(800);
  }
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
}

/** Network cut mid-session: the overlay, then the automatic return. */
async function connectionLost(page, label) {
  await proxy.set("down");
  await libraryNav(page).locator(".home-link").click();
  await page.locator("#lost-title").waitFor({ timeout: 25000 });
  await page.waitForTimeout(400);
  await noOverflow(page, `${label}-connection-lost`);
  const actionsBottom = await page
    .getByRole("button", { name: "Changer de serveur" })
    .evaluate((b) => b.getBoundingClientRect().bottom - innerHeight);
  check(
    `${label}_connection_lost_actions_on_screen`,
    actionsBottom <= 0,
    `${actionsBottom}px`,
  );
  await shot(page, `${label}-connection-lost`);
  await proxy.set("up");
  await page
    .locator("#lost-title")
    .waitFor({ state: "detached", timeout: 40000 });
}

let app = null;
let cdp = null;
try {
  app = await launch();
  const page = app.page;
  cdp = await page.context().newCDPSession(page);
  // The session must go through the proxy.
  const lost = page.locator("#lost-title");
  await Promise.race([
    startScreen(page),
    lost.waitFor({ timeout: 20000 }),
  ]).catch(() => {});
  if (await lost.count()) {
    // Left by an interrupted run (proxy gone): the same account signs in again
    // below, which replaces that session and its token.
    await page.getByRole("button", { name: "Changer de serveur" }).click();
    await page.locator("#signin-title").waitFor({ timeout: 10000 });
  } else if ((await startScreen(page)) === "signedIn") {
    await signOut(page);
  }
  await signIn(page, { ...account, url: proxy.url }, account.password);
  await homeHeading(page).waitFor({ timeout: 20000 });

  for (const size of SIZES) {
    await setScaledSize(app, cdp, size);
    const home = libraryNav(page).locator(".home-link");
    await home.click();
    await homeHeading(page).waitFor({ timeout: 15000 });
    await capture(page, `${size.name}-home`);

    const openFilm = await openFirst(page, "Films");
    await capture(page, `${size.name}-films`);
    await openFilm();
    await capture(page, `${size.name}-film-detail`);
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });

    const openSeries = await openFirst(page, "Séries");
    await openSeries();
    // One-season series show no season pills: wait for the episodes.
    await page
      .locator("ol.list li.episode")
      .first()
      .waitFor({ state: "attached", timeout: 15000 });
    await capture(page, `${size.name}-series-detail`);
    await page.mouse.wheel(0, 900);
    await capture(page, `${size.name}-series-episodes`);
    await page.keyboard.press("Escape");
    await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });

    await libraryNav(page)
      .getByRole("button", { name: "Réglages", exact: true })
      .click();
    await page.getByRole("heading", { name: "Réglages", level: 1 }).waitFor();
    await capture(page, `${size.name}-settings`);

    await player(page, size.name);
    await connectionLost(page, size.name);
  }

  await signOut(page);
  for (const size of SIZES) {
    await setScaledSize(app, cdp, size);
    await signInHeading(page).waitFor();
    await capture(page, `${size.name}-signin`);
  }
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  // Leave the direct server address as last server, not the proxy's.
  await signIn(page, account, account.password);
  await libraryNav(page).waitFor({ timeout: 20000 });
  await signOut(page);
  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (error) {
  check("scenario_error", false, error?.stack ?? String(error));
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) {
    try {
      if ((await libraryNav(app.page).count()) > 0) await signOut(app.page);
    } catch (error) {
      check("cleanup_error", false, String(error));
    }
    await app.close().catch(() => {});
  }
  await proxy.stop();
  finish();
}
