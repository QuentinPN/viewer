// Startup baseline on the real Emby test account (PRODUCT.md: establish baselines
// before setting targets or optimizing). Read only on the server. Three launches
// with the saved session; each one times, from the process start:
// - the page loaded, the home heading, the home rows answered, the hero image shown;
// then, in the last launch, five calls of each command the home and a grid use.
// Not a pass/fail gate: the checks only say each step was reached.
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  createReport,
  homeHeading,
  signIn,
  signOut,
  startScreen,
  testAccount,
} from "./scenario.mjs";

const account = testAccount();
const { check, finish } = createReport("measure-startup");
const LAUNCHES = 3;
const CALLS = 5;
// Warm artwork cache after the first launch, as for a user.
const cacheDir = mkdtempSync(join(tmpdir(), "viewer-e2e-measure-"));

const median = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
};

/** Milliseconds of each call of `command`, run one after the other. */
const timeCommand = (page, command, args) =>
  page.evaluate(
    async ({ command, args, calls }) => {
      const out = [];
      for (let i = 0; i < calls; i += 1) {
        const t = performance.now();
        await window.__viewer.invoke(command, args);
        out.push(Math.round(performance.now() - t));
      }
      return out;
    },
    { command, args, calls: CALLS },
  );

let app = null;
try {
  for (let run = 1; run <= LAUNCHES; run += 1) {
    const t0 = Date.now();
    app = await launch({ env: { VIEWER_E2E_ARTWORK_DIR: cacheDir } });
    const page = app.page;
    const loaded = Date.now() - t0;
    if ((await startScreen(page)) === "signedOut") {
      if (run > 1) throw new Error("session not kept between launches");
      await signIn(page, account, account.password);
    }
    await homeHeading(page).waitFor({ timeout: 20000 });
    const heading = Date.now() - t0;
    await page.locator(".home[aria-busy=false]").waitFor({ timeout: 20000 });
    const rows = Date.now() - t0;
    await page.waitForFunction(
      () => {
        const img = document.querySelector(".hero img");
        return (
          img instanceof HTMLImageElement &&
          img.complete &&
          img.naturalWidth > 0
        );
      },
      null,
      { timeout: 20000 },
    );
    const hero = Date.now() - t0;
    check(
      `launch_${run}`,
      true,
      `page ${loaded} ms, Accueil ${heading} ms, rangées ${rows} ms, image du héros ${hero} ms${run === 1 ? " (connexion comprise)" : ""}`,
    );

    if (run === LAUNCHES) {
      const libraries = await page.evaluate(() =>
        window.__viewer.invoke("emby_libraries"),
      );
      const films = libraries.find((l) => l.kind === "movies");
      const commands = [
        ["emby_libraries", {}],
        ["emby_hero", {}],
        ["emby_continue_watching", {}],
        ["emby_next_up", {}],
        ["emby_favorites", {}],
        ["emby_latest", { libraryId: films.id }],
        [
          "emby_items",
          { libraryId: films.id, startIndex: 0, limit: 60, options: null },
        ],
      ];
      for (const [command, args] of commands) {
        const ms = await timeCommand(page, command, args);
        check(
          `command_${command}`,
          true,
          `médiane ${median(ms)} ms (${ms.join(", ")})`,
        );
      }
      await signOut(page);
    }
    check(`exit_code_${run}`, (await app.close()) === 0);
    app = null;
  }
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
} finally {
  if (app) await app.close().catch(() => {});
  rmSync(cacheDir, { recursive: true, force: true });
  finish();
}
