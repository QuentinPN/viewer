// Redesigned screens (ADR 0031) at the CSS sizes Windows display scaling gives,
// on the real Emby test account, driven over CDP. Windows scaling shrinks the CSS
// viewport and raises devicePixelRatio; the scenario reproduces both without
// touching the system setting: the real window is resized to the CSS size and
// CDP sets the device scale factor (so srcset picks the images it would pick).
// Layout equivalent only: font rasterization and the native frame are not the
// ones of a real 125 % or 150 % screen.
// For each size: home, film library, film detail, series detail, settings, then
// the sign-in screen after signing out. No horizontal page overflow allowed.
// Read only on the server.
import { launch } from "./app.mjs";
import {
  createReport,
  detailTitle,
  homeHeading,
  libraryNav,
  resizeWindow,
  signIn,
  signInHeading,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-scaling");

// A 1280 × 720 window at 125 % and 150 %, and a 1920 × 1080 screen at 150 %
// (same CSS size as 1280 × 720 at 100 %, sharper).
const SIZES = [
  { name: "125", width: 1024, height: 576, scale: 1.25 },
  { name: "150", width: 853, height: 480, scale: 1.5 },
  { name: "1080p150", width: 1280, height: 720, scale: 1.5 },
];

const libraryButton = (page, kind) =>
  libraryNav(page).locator("ul.libraries button", { hasText: kind }).first();

async function setSize(app, cdp, { width, height, scale }) {
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  resizeWindow(app.proc.pid, width, height);
  await app.page
    .waitForFunction((w) => Math.abs(innerWidth - w) <= 24, width, {
      timeout: 10000,
    })
    .catch(async () => {
      const got = await app.page.evaluate(() => `${innerWidth}×${innerHeight}`);
      throw new Error(`window is ${got}, wanted ${width}×${height}`);
    });
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 0,
    height: 0,
    deviceScaleFactor: scale,
    mobile: false,
  });
  await app.page.waitForFunction((s) => devicePixelRatio === s, scale, {
    timeout: 10000,
  });
  await app.page.waitForTimeout(500);
}

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

let app = null;
let cdp = null;
try {
  app = await launch();
  const page = app.page;
  cdp = await page.context().newCDPSession(page);
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await homeHeading(page).waitFor({ timeout: 20000 });

  for (const size of SIZES) {
    await setSize(app, cdp, size);
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
  }

  await signOut(page);
  for (const size of SIZES) {
    await setSize(app, cdp, size);
    await signInHeading(page).waitFor();
    await capture(page, `${size.name}-signin`);
  }
  await cdp.send("Emulation.clearDeviceMetricsOverride");
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
  finish();
}
