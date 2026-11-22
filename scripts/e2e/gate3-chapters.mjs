// Gate 3 chapters on the real Emby test account, driven over CDP:
// - a film without resume point among the first cards, with at least four chapters
//   from Emby (emby_chapters);
// - played, the seek bar shows one mark per chapter start but the file's, and the
//   "Chapitres" button opens a panel listing them, the playing one marked and
//   focused;
// - ↓ ↓ then Enter seeks to the third chapter and closes the panel;
// - Page Down goes to the next chapter, Page Up right after back to the one before;
//   the name of the playing chapter is shown under the seek bar;
// - Escape closes the panel and the player stays;
// - mpv's own chapter count is reported next to Emby's (not required to match: a
//   transcode has none).
// Server side effects: none kept. The playback is rewound to 0 before stopping, so
// no resume point is left.
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

const FILMS_TO_TRY = 15;

const account = testAccount();
const { check, shot, finish } = createReport("gate3-chapters");

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
const chaptersButton = (page) =>
  page
    .locator(".player")
    .getByRole("button", { name: "Chapitres", exact: true });
const panel = (page) => page.getByRole("dialog", { name: "Chapitres" });
const near = (d, seconds) =>
  d?.ok && Math.abs((d.timePos ?? -99) - seconds) < 2;
const landOn = (page, seconds) =>
  waitForDiagnostics(page, (d) => near(d, seconds), 10000);

let app = await launch();
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

  // 1. A film without resume point, with at least four chapters.
  let film = null;
  const count = Math.min(FILMS_TO_TRY, await cards(page).count());
  for (let n = 0; n < count; n++) {
    const id = await cards(page).nth(n).getAttribute("data-item-id");
    const detail = await call(page, "emby_item_detail", { itemId: id });
    if (detail.resume !== null) continue;
    const chapters = await call(page, "emby_chapters", { itemId: id });
    if (chapters.length >= 4) {
      film = { n, id, chapters };
      break;
    }
  }
  if (!film) throw new Error(`aucun film à chapitres sur ${count} cartes`);
  const { chapters } = film;
  check(
    "film_found",
    true,
    `carte ${film.n + 1}, ${chapters.length} chapitres : ${chapters
      .slice(0, 4)
      .map((c) => `${c.name} ${c.start.toFixed(1)}`)
      .join(", ")}…`,
  );
  check(
    "chapters_sorted",
    chapters.every((c, i) => i === 0 || c.start > chapters[i - 1].start),
  );

  await cards(page).nth(film.n).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await page.locator(".detail button.play").first().click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 1, 20000);
  check("film_plays", started?.ok && started.timePos > 1);
  const decision = (await playerBadge(page).textContent()).trim();
  check(
    "mpv_chapter_count",
    true,
    `mpv ${started?.chapterCount ?? "aucun"}, Emby ${chapters.length} (${decision})`,
  );

  // 2. Marks on the seek bar, one per start inside the duration but the file's.
  await chaptersButton(page).waitFor({ timeout: 10000 });
  const duration = started.duration ?? 0;
  const expected = chapters.filter(
    (c) => c.start > 0 && c.start < duration,
  ).length;
  const marks = await page.locator(".player .timeline .marks span").count();
  check("seek_bar_marks", marks === expected, `${marks} / ${expected}`);
  const valuetext = await page
    .locator(".player .timeline input[type=range]")
    .getAttribute("aria-valuetext");
  check(
    "seek_bar_says_chapter",
    valuetext?.endsWith(`, ${chapters[0].name}`),
    valuetext,
  );

  // 3. The panel: every chapter, the playing one marked and focused.
  await chaptersButton(page).click();
  await panel(page).waitFor({ timeout: 5000 });
  const rows = panel(page).locator("button.row");
  check("panel_rows", (await rows.count()) === chapters.length);
  const current = await panel(page)
    .locator("button.row[aria-current='true']")
    .textContent();
  check("current_row", current?.includes(chapters[0].name), current?.trim());
  const focused = await page.evaluate(
    () => document.activeElement?.getAttribute("aria-current") === "true",
  );
  check("current_row_focused", focused);
  await shot(page, "panel");

  // 4. ↓ ↓ Enter: the third chapter, panel closed, volume untouched.
  const volume = (await diagnostics(page)).volume;
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  const third = await landOn(page, chapters[2].start);
  check(
    "row_seeks",
    near(third, chapters[2].start),
    `${third?.timePos?.toFixed(1)} s pour ${chapters[2].start.toFixed(1)} s`,
  );
  check("panel_closed_after_row", (await panel(page).count()) === 0);
  check("arrows_keep_volume", third?.volume === volume);
  await page
    .locator(".player .chapter-name", { hasText: chapters[2].name })
    .waitFor({ timeout: 5000 });
  check("chapter_name_shown", true, chapters[2].name);

  // 5. Page Down, then Page Up right after.
  await page.keyboard.press("PageDown");
  const fourth = await landOn(page, chapters[3].start);
  check(
    "page_down_next",
    near(fourth, chapters[3].start),
    `${fourth?.timePos?.toFixed(1)} s`,
  );
  await page.keyboard.press("PageUp");
  const back = await landOn(page, chapters[2].start);
  check(
    "page_up_previous",
    near(back, chapters[2].start),
    `${back?.timePos?.toFixed(1)} s`,
  );
  await shot(page, "chapter-name");

  // 6. Escape closes the panel and leaves the player open.
  await chaptersButton(page).click();
  await panel(page).waitFor({ timeout: 5000 });
  await page.keyboard.press("Escape");
  await page.waitForTimeout(300);
  check(
    "escape_closes_panel",
    (await panel(page).count()) === 0 &&
      (await playerBadge(page).count()) === 1,
  );

  // 7. Back to 0, then leave.
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const rewound = await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
  );
  check("rewound", rewound?.ok && rewound.timePos < 3);
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  check(
    "no_resume_left",
    (await call(page, "emby_item_detail", { itemId: film.id })).resume === null,
  );

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
  finish();
}
