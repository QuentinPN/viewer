// Navigation pill (ADR 0031) with many libraries, at the CSS sizes of 125 % and
// 150 % scaling, on the real Emby test account driven over CDP. The local proxy
// `net-proxy.mjs` ("many-views") answers the library list with each library
// repeated, so the server keeps its own libraries: it is only read. The made-up
// libraries are never opened (their ids are unknown to the server).
// Checks: every library is listed, the pill stays in the window, Réglages and the
// account button stay visible, the last library is reachable with the mouse wheel,
// the content does not scroll with the list, and in a narrow window the focused
// search field covers the pill once the list is scrolled.
import { launch } from "./app.mjs";
import { startProxy } from "./net-proxy.mjs";
import {
  createReport,
  homeHeading,
  libraryNav,
  setScaledSize,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
if (!account.url || /^http:\/\/(127\.0\.0\.1|localhost)/.test(account.url))
  throw new Error(
    `VIEWER_EMBY_URL must be the server address, got ${account.url}`,
  );
const { check, shot, finish } = createReport("gate3-nav-many");
const proxy = await startProxy(account.url);

const SIZES = [
  { name: "150", width: 853, height: 480, scale: 1.5 },
  { name: "125", width: 1024, height: 576, scale: 1.25 },
];

/** True when the element is on screen and is what a click at its center hits. */
const hittable = (locator) =>
  locator.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    if (x < 0 || x > innerWidth || y < 0 || y > innerHeight) return false;
    return el.contains(document.elementFromPoint(x, y));
  });

let app = null;
try {
  app = await launch();
  const page = app.page;
  const cdp = await page.context().newCDPSession(page);
  const lost = page.locator("#lost-title");
  await Promise.race([
    startScreen(page),
    lost.waitFor({ timeout: 20000 }),
  ]).catch(() => {});
  if (await lost.count()) {
    // Left by an interrupted run (proxy gone).
    await page.getByRole("button", { name: "Changer de serveur" }).click();
    await page.locator("#signin-title").waitFor({ timeout: 10000 });
  } else if ((await startScreen(page)) === "signedIn") {
    await signOut(page);
  }
  await proxy.set("many-views");
  await signIn(page, { ...account, url: proxy.url }, account.password);
  await homeHeading(page).waitFor({ timeout: 20000 });
  const nav = libraryNav(page);
  const list = nav.locator("ul.libraries");
  const buttons = list.locator("button");
  await buttons.first().waitFor({ timeout: 15000 });
  const count = await buttons.count();
  check("libraries_repeated", count >= 8 && count % 4 === 0, `${count}`);

  for (const size of SIZES) {
    const label = size.name;
    await setScaledSize(app, cdp, size);
    const pill = await nav
      .locator(".pill")
      .evaluate((p) => [
        p.getBoundingClientRect().left,
        p.getBoundingClientRect().right,
      ]);
    check(
      `${label}_pill_in_window`,
      pill[0] >= 0 && pill[1] <= size.width,
      `${Math.round(pill[0])}..${Math.round(pill[1])}`,
    );
    const settings = nav.getByRole("button", { name: "Réglages", exact: true });
    const avatar = nav.locator("button.avatar");
    const search = page.locator("#search-field");
    check(`${label}_settings_visible`, await hittable(settings));
    check(`${label}_account_visible`, await hittable(avatar));
    check(`${label}_search_visible`, await hittable(search));
    check(
      `${label}_end_fades`,
      await list.evaluate((l) => l.classList.contains("fade-end")),
    );
    await shot(page, `${label}-nav`);

    const last = buttons.last();
    const box = await list.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    for (let i = 0; i < 8 && !(await hittable(last)); i++) {
      await page.mouse.wheel(0, 240);
      await page.waitForTimeout(250);
    }
    check(`${label}_last_library_reachable_by_wheel`, await hittable(last));
    check(`${label}_settings_visible_after_scroll`, await hittable(settings));
    check(
      `${label}_content_not_scrolled`,
      (await page.evaluate(
        () => document.querySelector(".pane main").scrollTop,
      )) === 0,
    );
    await shot(page, `${label}-nav-scrolled`);

    await page.keyboard.press("Control+f");
    await page.waitForTimeout(400);
    const covers = await page.evaluate(() => {
      const p = document.querySelector(".pill").getBoundingClientRect();
      const s = document.querySelector(".search-box").getBoundingClientRect();
      return (
        s.left >= p.left - 1 && s.right <= p.right + 1 && s.width > p.width / 2
      );
    });
    // Above 900 px the field only widens; it covers the pill below.
    check(
      `${label}_search_${size.width <= 900 ? "covers" : "inside"}_pill`,
      size.width <= 900 ? covers : await hittable(search),
    );
    await shot(page, `${label}-nav-search`);
    await page.keyboard.press("Escape");
    await page.evaluate(() => {
      document.activeElement?.blur();
      for (const el of document.querySelectorAll(".pill, .libraries"))
        el.scrollLeft = 0;
    });
  }

  await cdp.send("Emulation.clearDeviceMetricsOverride");
  await signOut(page);
  // Leave the direct server address as last server, not the proxy's.
  await proxy.set("up");
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
