// Gate 4 watched state at the natural end of a film (ADR 0008), on the real Emby
// test account, driven over CDP: an unwatched film without resume point, played
// to its end, is marked watched by the server from the stop report alone (the app
// never calls PlayedItems), keeps no resume point, and its detail page shows it.
// Server side effects: the film is marked played; the scenario marks it unplayed
// again at the end (cargo test real_server_mark_unplayed).
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  libraryNav,
  playerBadge,
  signIn,
  signOut,
  startScreen,
  testAccount,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate4-watched");
const root = join(import.meta.dirname, "..", "..");

const call = async (page, cmd, args) => {
  const r = await page.evaluate(
    ([c, a]) =>
      window.__TAURI_INTERNALS__
        .invoke(c, a)
        .then((value) => ({ value }))
        .catch((e) => ({ error: JSON.stringify(e) })),
    [cmd, args],
  );
  if (r.error) throw new Error(`${cmd} : ${r.error}`);
  return r.value;
};
const detailOf = (page, id) => call(page, "emby_item_detail", { itemId: id });

let app = await launch();
let film = null;
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await libraryNav(page).waitFor({ timeout: 20000 });
  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });

  // 1. An unwatched film without resume point.
  for (let n = 0; n < Math.min(15, await cards(page).count()); n++) {
    const id = await cards(page).nth(n).getAttribute("data-item-id");
    const d = await detailOf(page, id);
    if (!d.played && d.resume == null) {
      film = { n, id };
      break;
    }
  }
  check("film_found", film !== null, film && `carte ${film.n + 1}`);
  if (!film) throw new Error("aucun film non vu sans point de reprise");

  // 2. Played to its natural end: the player closes by itself.
  await cards(page).nth(film.n).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await page.locator(".detail button.play").first().click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 1, 20000);
  check("film_plays", started?.ok, `${started?.duration?.toFixed(0)} s`);
  await call(page, "player_command", {
    command: {
      type: "seekAbsolute",
      seconds: Math.max(0, started.duration - 4),
    },
  });
  await playerBadge(page).waitFor({ state: "detached", timeout: 30000 });
  await detailTitle(page).waitFor({ timeout: 20000 });
  check("back_on_detail_at_end", true);

  // 3. Watched on the server, without resume point; shown on the detail page.
  let after = null;
  for (let i = 0; i < 10 && !after?.played; i++) {
    await page.waitForTimeout(1000);
    after = await detailOf(page, film.id);
  }
  check("played_on_server", after?.played === true, `vu ${after?.played}`);
  check(
    "no_resume_point",
    after?.resume == null,
    JSON.stringify(after?.resume),
  );
  const pill = page.locator(".detail .status .pill.done");
  await pill.waitFor({ timeout: 5000 }).catch(() => {});
  const shown = (await pill.count()) ? await pill.textContent() : "";
  check("detail_shows_watched", shown?.trim() === "Déjà vu", shown);
  await shot(page, "detail-watched");

  await page.keyboard.press("Escape");
  await signOut(page);
  check("exit_code", (await app.close()) === 0);
  app = null;
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "error").catch(() => {});
} finally {
  if (app) await app.close().catch(() => {});
  if (film) {
    const r = spawnSync(
      "powershell",
      [
        "-NoProfile",
        "-File",
        join(root, "scripts", "with-msvc.ps1"),
        "cargo",
        "test",
        "--manifest-path",
        join(root, "src-tauri", "Cargo.toml"),
        "real_server_mark_unplayed",
        "--",
        "--ignored",
        "--nocapture",
      ],
      {
        cwd: root,
        encoding: "utf8",
        env: { ...process.env, VIEWER_UNPLAY_IDS: film.id },
      },
    );
    check(
      "film_unplayed_again",
      r.status === 0 && r.stdout.includes(`${film.id} unplayed`),
      r.status === 0 ? film.id : (r.stdout + r.stderr).slice(-300),
    );
  }
  finish();
}
