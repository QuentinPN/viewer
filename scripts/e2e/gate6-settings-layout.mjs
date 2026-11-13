// Gate 6 settings layout: the sections fill the window in columns, as many as fit
// (four at most), and fall back to one column on a narrow window. Read only.
import { launch } from "./app.mjs";
import {
  createReport,
  libraryNav,
  resizeWindow,
  signIn,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate6-settings-layout");

/** Columns per group, group contents, and whether any section overflows the page. */
const layout = (page) =>
  page.evaluate(() => {
    const panels = [...document.querySelectorAll(".columns > section")];
    // Column count of the widest group: distinct left edges within each group
    const columnsOf = (sel) =>
      new Set(
        [...document.querySelectorAll(`${sel} > section`)].map((p) =>
          Math.round(p.getBoundingClientRect().left),
        ),
      ).size;
    const width = document.documentElement.clientWidth;
    const titles = (sel) =>
      [...document.querySelectorAll(`${sel} > section h2`)].map((h) =>
        h.textContent.trim(),
      );
    return {
      everyday: titles(".columns.everyday"),
      // Lecture and Qualité de lecture stacked in the same column
      stacked: (() => {
        const left = (id) =>
          Math.round(
            document
              .getElementById(id)
              ?.closest("section")
              ?.getBoundingClientRect().left ?? -1,
          );
        return left("settings-playback") === left("settings-quality");
      })(),
      quiet: titles(".columns.quiet"),
      columns: Math.max(
        columnsOf(".columns.everyday"),
        columnsOf(".columns.quiet"),
      ),
      sections: panels.length,
      // A section past the page, or anything inside a section past its edge
      overflow: panels.some((p) => {
        const right = p.getBoundingClientRect().right;
        return (
          right > width + 1 ||
          [...p.querySelectorAll("*")].some(
            (c) => c.getBoundingClientRect().right > right + 1,
          )
        );
      }),
      hscroll: document.documentElement.scrollWidth > width + 1,
    };
  });

let app = null;
try {
  app = await launch();
  const page = app.page;
  if ((await startScreen(page)) === "signedOut")
    await signIn(page, account, account.password);
  await libraryNav(page)
    .getByRole("button", { name: "Réglages", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Réglages", level: 1 })
    .waitFor({ timeout: 15000 });
  for (const [width, height, min, max] of [
    [2000, 1100, 3, 4],
    [1400, 900, 2, 3],
    [800, 700, 1, 1],
  ]) {
    resizeWindow(app.proc.pid, width, height);
    await page.waitForTimeout(800);
    const l = await layout(page);
    check(
      `columns_${width}`,
      l.columns >= min && l.columns <= max && l.sections >= 8,
      `${l.columns} colonne(s), ${l.sections} sections`,
    );
    if (width === 2000)
      check(
        "groups",
        l.everyday[0] === "Lecture" &&
          l.everyday.includes("Accueil") &&
          l.quiet.includes("Diagnostics") &&
          l.quiet.includes("Cache des images") &&
          l.stacked,
        `quotidien : ${l.everyday.join(", ")} | maintenance : ${l.quiet.join(", ")}`,
      );
    check(`no_overflow_${width}`, !l.overflow && !l.hscroll);
    await shot(page, `settings-${width}`);
    if (width === 2000) {
      await page.locator(".columns.quiet").scrollIntoViewIfNeeded();
      await shot(page, "settings-2000-maintenance");
      await page.evaluate(() => window.scrollTo(0, 0));
    }
  }
  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (e) {
  check("scenario", false, e?.stack ?? e);
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) await app.close().catch(() => {});
  finish();
}
