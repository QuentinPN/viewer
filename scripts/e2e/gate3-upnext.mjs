// Gate 3 next episode at the end of an episode on the real Emby test
// account, driven over CDP:
// - an episode played to its end shows the "Épisode suivant" card: the next
//   episode's code and title (the server's and the series page's), a countdown
//   that goes down, the focus on "Lire maintenant", an opaque background;
//   Escape cancels back to the series page;
// - left alone, the next episode starts after 10 s in the same player, still
//   fullscreen;
// - "Lire maintenant" starts it at once.
// Server side effects: the first episode is marked played each time it reaches its
// end; when it was unplayed before, the scenario marks it unplayed again at the
// end (cargo test real_server_mark_unplayed). The next episode is rewound to 0
// before stopping, so it keeps no resume point.
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  diagnostics,
  libraryNav,
  playerBadge,
  signIn,
  signOut,
  startScreen,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-upnext");
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
const command = (page, c) => call(page, "player_command", { command: c });
const detailOf = (page, id) => call(page, "emby_item_detail", { itemId: id });
const card = (page) => page.getByRole("dialog", { name: "Épisode suivant" });
const button = (page, name) =>
  page.locator(".player").getByRole("button", { name, exact: true });
const text = async (locator) => (await locator.textContent())?.trim() ?? "";
const secondsLeft = async (page) =>
  Number(
    (await text(card(page).locator(".countdown"))).match(/(\d+) s/)?.[1] ?? NaN,
  );

/** Plays the episode of `row` and jumps to 4 s before its end. */
async function playToEnd(page, row, label, { fullscreen = false } = {}) {
  await row.locator("button.thumb").click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 1, 20000);
  if (fullscreen) {
    await page.keyboard.press("f");
    await button(page, "Quitter le plein écran").waitFor({ timeout: 5000 });
  }
  await command(page, {
    type: "seekAbsolute",
    seconds: Math.max(0, started.duration - 4),
  });
  await card(page).waitFor({ timeout: 20000 });
  check(`${label}_card_shown`, true, `${started.duration.toFixed(0)} s`);
}

/** Back to 0, then Escape: the server keeps no resume point. */
async function rewindAndLeave(page, label) {
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const rewound = await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
  );
  check(`${label}_rewound`, rewound?.ok && rewound.timePos < 3);
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
  await page.waitForTimeout(500);
  const after = await diagnostics(page);
  check(`${label}_engine_stopped`, !after.ok || after.timePos == null);
}

/** The next episode plays in the player: its title, and the engine moves. */
async function nextPlays(page, label, next, code) {
  await page
    .locator(".player .heading h1", { hasText: next.name })
    .waitFor({ timeout: 20000 });
  const moving = await waitForPosition(page, 1, 20000);
  const kicker = await text(page.locator(".player .heading .kicker"));
  check(
    `${label}_next_plays`,
    moving?.ok && kicker.endsWith(code) && (await card(page).count()) === 0,
    `${kicker} — ${next.name} à ${moving?.timePos?.toFixed(1)} s`,
  );
}

let app = await launch();
let first = null;
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
  await libraries.nth(kinds.indexOf("Séries")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  await detailTitle(page).waitFor({ timeout: 20000 });

  // The first episode of the list and its successor, as the server gives it.
  const rows = page.locator("ol.list li.episode:not(.skeleton)");
  await rows.nth(1).waitFor({ timeout: 20000 });
  const firstRow = rows.first();
  const firstId = await firstRow.getAttribute("data-episode-id");
  const before = await detailOf(page, firstId);
  first = { id: firstId, played: before.played };
  const next = before.episode?.next;
  if (!next) throw new Error("le premier épisode n'a pas de suivant");
  const nextBefore = await detailOf(page, next.id);
  const code = `S${next.seasonIndex} · É${next.index}`;
  const secondRowName = await text(rows.nth(1).locator("button.open"));
  check(
    "next_known",
    secondRowName === next.name,
    `${code} — ${next.name} (liste : ${secondRowName})`,
  );

  // 1. To the end: the card, its countdown, its focus; Escape cancels.
  await playToEnd(page, firstRow, "cancel");
  const label = await text(card(page).locator(".label"));
  const title = await text(card(page).locator("h2"));
  const kicker = await text(card(page).locator(".kicker"));
  check(
    "card_content",
    label === "Épisode suivant" && title === next.name && kicker.endsWith(code),
    `${kicker} — ${title}`,
  );
  const leftAtFirst = await secondsLeft(page);
  await page.waitForTimeout(2200);
  const leftLater = await secondsLeft(page);
  check(
    "countdown_goes_down",
    leftAtFirst <= 10 && leftLater < leftAtFirst && leftLater > 0,
    `${leftAtFirst} → ${leftLater} s`,
  );
  const focused = await page.evaluate(() => {
    const e = document.activeElement;
    const style = e ? getComputedStyle(e) : null;
    return {
      name: e?.textContent?.trim(),
      outline: style ? `${style.outlineStyle} ${style.outlineWidth}` : "",
    };
  });
  check(
    "focus_on_play_now",
    focused.name === "Lire maintenant" &&
      focused.outline.startsWith("solid") &&
      focused.outline !== "solid 0px",
    `anneau : ${focused.outline}`,
  );
  const background = await page
    .locator(".player")
    .evaluate((e) => getComputedStyle(e).backgroundColor);
  check(
    "opaque_background",
    background !== "rgba(0, 0, 0, 0)" && background !== "transparent",
    background,
  );
  check(
    "bottom_controls_replaced",
    (await page.locator(".player .bottom").count()) === 0,
  );
  await shot(page, "up-next-card");
  await page.keyboard.press("Escape");
  await card(page).waitFor({ state: "detached", timeout: 10000 });
  await playerBadge(page).waitFor({ state: "detached", timeout: 10000 });
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  await page.waitForTimeout(800);
  const afterCancel = await diagnostics(page);
  check(
    "escape_cancels",
    !afterCancel.ok || afterCancel.timePos == null,
    "lecteur fermé, page de la série",
  );

  // 2. Left alone: the next episode starts after the countdown, still fullscreen.
  await playToEnd(page, firstRow, "auto", { fullscreen: true });
  const shownAt = Date.now();
  await nextPlays(page, "auto", next, code);
  const waited = (Date.now() - shownAt) / 1000;
  check("auto_after_countdown", waited >= 8, `${waited.toFixed(1)} s`);
  check(
    "fullscreen_kept",
    (await button(page, "Quitter le plein écran").count()) === 1,
  );
  await shot(page, "next-episode-playing");
  await page.keyboard.press("f");
  await button(page, "Plein écran").waitFor({ timeout: 5000 });
  await rewindAndLeave(page, "auto");
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });

  // 3. "Lire maintenant" starts it at once.
  await playToEnd(page, firstRow, "now");
  const clickedAt = Date.now();
  await card(page).getByRole("button", { name: "Lire maintenant" }).click();
  await nextPlays(page, "now", next, code);
  const took = (Date.now() - clickedAt) / 1000;
  check("play_now_at_once", took < 8, `${took.toFixed(1)} s`);
  await rewindAndLeave(page, "now");

  // 4. The next episode is as before on the server.
  await page.waitForTimeout(1500);
  const nextAfter = await detailOf(page, next.id);
  check(
    "next_episode_unchanged",
    nextAfter.played === nextBefore.played &&
      JSON.stringify(nextAfter.resume) === JSON.stringify(nextBefore.resume),
    `vu ${nextAfter.played}, reprise ${JSON.stringify(nextAfter.resume)}`,
  );
  const firstAfter = await detailOf(page, firstId);
  check("first_episode_played", firstAfter.played === true);

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
  // Undo the played mark of the first episode, which was unplayed before.
  if (first && !first.played) {
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
        env: { ...process.env, VIEWER_UNPLAY_IDS: first.id },
      },
    );
    check(
      "first_episode_unplayed_again",
      r.status === 0 && r.stdout.includes(`${first.id} unplayed`),
      r.status === 0 ? first.id : (r.stdout + r.stderr).slice(-300),
    );
  }
  finish();
}
