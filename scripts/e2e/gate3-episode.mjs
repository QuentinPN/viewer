// Gate 3 episode page (ADR 0016) on the real Emby test account, driven over CDP:
// - in a series, the thumbnail plays and the title opens the episode page;
// - the page shows the series, the season and episode, the image, the play
//   button, the technical info and no Favori button; the series page under it
//   is hidden and inert;
// - "Épisode suivant" crosses from the last episode of a season to the next
//   season, "Épisode précédent" comes back; the focus stays on the button used;
// - Escape goes back to the series on the episode shown last, in its season,
//   with the focus on its title;
// - a short playback from the page and from a thumbnail; Escape after the
//   player keeps the page it was started from.
// Starts two playbacks and stops them within seconds (no resume point is kept).
import { launch } from "./app.mjs";
import {
  cards,
  createReport,
  detailTitle,
  diagnostics,
  episodeTitle,
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
const { check, shot, finish } = createReport("gate3-episode");

const invoke = (page, cmd, args) =>
  page.evaluate(
    ([c, a]) =>
      window.__TAURI_INTERNALS__
        .invoke(c, a)
        .then((value) => ({ value }))
        .catch((e) => ({ error: JSON.stringify(e) })),
    [cmd, args],
  );
const call = async (page, cmd, args) => {
  const r = await invoke(page, cmd, args);
  if (r.error) throw new Error(`${cmd} : ${r.error}`);
  return r.value;
};
const episodePage = (page) =>
  page.locator("section.detail", { has: episodeTitle(page) });
const episodeRow = (page, id) =>
  page.locator(`ol.list li.episode[data-episode-id="${id}"]`);
const activeInfo = (page) =>
  page.evaluate(() => {
    const a = document.activeElement;
    return {
      id: a?.id ?? "",
      className: a?.className ?? "",
      episode: a?.closest("[data-episode-id]")?.getAttribute("data-episode-id"),
    };
  });
const pressedSeason = (page) =>
  page
    .locator('.seasons button[aria-pressed="true"]')
    .evaluate((b) => b.firstChild.textContent.trim());
const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__TAURI_INTERNALS__.invoke("player_command", { command: c }),
    cmd,
  );

/**
 * Plays a little, then back to 0 and Escape: the server keeps no resume point
 * (also clears one left by an interrupted run). Returns what was checked.
 */
async function shortPlay(page) {
  await playerBadge(page).waitFor({ timeout: 20000 });
  const decision = (await playerBadge(page).textContent()).trim();
  const started = await waitForPosition(page, 1, 20000);
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const rewound = await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
  );
  await page.keyboard.press("Escape");
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
  await page.waitForTimeout(500);
  const after = await diagnostics(page);
  return {
    ok:
      started?.ok && started.timePos > 1 && rewound?.ok && rewound.timePos < 3,
    stopped: !after.ok || after.timePos == null,
    detail: `${decision}, ${started?.timePos?.toFixed(1)} s puis ${rewound?.timePos?.toFixed(1)} s`,
  };
}
const resumeOf = async (page, id) =>
  (await call(page, "emby_item_detail", { itemId: id })).resume;

/** Title text, or the alt text of the logo that replaces it. */
const textOrAlt = (locator) =>
  locator.evaluate(
    (e) => e.textContent.trim() || e.querySelector("img")?.alt || "",
  );

async function waitEpisode(page, name) {
  await page.waitForFunction(
    (n) => {
      const h = document.querySelector("#episode-title");
      const s = h?.closest("section.detail");
      return (
        h?.textContent.trim() === n &&
        s?.getAttribute("aria-busy") === "false" &&
        !!s.querySelector(".play-actions")
      );
    },
    name,
    { timeout: 20000 },
  );
}

async function listLoaded(page) {
  await page
    .locator("ol.list li.episode:not(.skeleton)")
    .first()
    .waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () =>
      document.querySelector("ol.list")?.getAttribute("aria-busy") !== "true",
    null,
    { timeout: 20000 },
  );
}

const app = await launch();
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await libraryNav(page).waitFor({ timeout: 20000 });

  // 1. A series with two regular seasons whose first fits in one page.
  await libraryNav(page)
    .locator("ul.libraries button", { hasText: "Séries" })
    .first()
    .click();
  await cards(page).first().waitFor({ timeout: 20000 });
  let target = null;
  for (let n = 0; n < Math.min(15, await cards(page).count()); n++) {
    const id = await cards(page).nth(n).getAttribute("data-item-id");
    const seasons = (await call(page, "emby_seasons", { seriesId: id })).filter(
      (s) => (s.index ?? 0) > 0,
    );
    if (seasons.length < 2) continue;
    const eps = await call(page, "emby_episodes", {
      seriesId: id,
      seasonId: seasons[0].id,
      startIndex: 0,
      limit: 50,
    });
    const regular = eps.items.filter((e) => e.seasonIndex === seasons[0].index);
    if (regular.length < 2 || eps.total > 50) continue;
    target = { n, id, seasons, regular };
    break;
  }
  check(
    "series_found",
    target !== null,
    target && `carte ${target.n + 1}, ${target.regular.length} épisodes`,
  );
  if (!target) throw new Error("aucune série à deux saisons");

  await cards(page).nth(target.n).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  const seriesName = await textOrAlt(detailTitle(page));
  await listLoaded(page);
  const [s1, s2] = target.seasons;
  if ((await pressedSeason(page)) !== s1.name) {
    await page
      .getByRole("group", { name: "Saisons" })
      .getByRole("button", { name: s1.name })
      .first()
      .click();
  }
  const last = target.regular.at(-1);
  const before = target.regular.at(-2);
  await episodeRow(page, last.id).waitFor({ timeout: 20000 });

  // 2. Thumbnail and title are two separate controls.
  const row = episodeRow(page, last.id);
  const thumbLabel = await row
    .locator("button.thumb")
    .getAttribute("aria-label");
  check(
    "thumb_plays_title_opens",
    /^(Lire|Reprendre) /.test(thumbLabel) &&
      (await row.locator("button.open").textContent()).trim() === last.name,
    thumbLabel,
  );

  // 3. The title opens the page, by keyboard.
  await row.locator("button.open").focus();
  await page.keyboard.press("Enter");
  await episodeTitle(page).waitFor({ timeout: 20000 });
  await waitEpisode(page, last.name);
  const ep = episodePage(page);
  const positionText = (await ep.locator(".position").textContent()).trim();
  check(
    "series_and_position",
    (await textOrAlt(ep.locator("p.series"))) === seriesName &&
      positionText.startsWith(`Saison ${s1.index} · Épisode`),
    `${seriesName} — ${positionText}`,
  );
  check(
    "image_play_no_favorite",
    (await ep.locator("img.backdrop").count()) === 1 &&
      (await ep.locator(".play-actions button.play").count()) === 1 &&
      (await ep.locator(".play-actions button.favorite").count()) === 0,
  );
  const facts = await ep
    .locator("dl dt")
    .evaluateAll((els) => els.map((e) => e.textContent.trim()));
  check(
    "facts_and_tech",
    !facts.includes("Sortie") &&
      !facts.includes("Première diffusion") &&
      (await ep.locator("section.tech").count()) === 1,
    facts.join(", "),
  );
  check(
    "series_page_hidden_and_inert",
    await page.evaluate(() => {
      const s = document
        .querySelector("#detail-title")
        ?.closest("section.detail");
      return (
        !!s &&
        s.inert &&
        getComputedStyle(s).visibility === "hidden" &&
        !s.checkVisibility({ visibilityProperty: true })
      );
    }),
  );
  check(
    "unique_ids",
    await page.evaluate(
      () =>
        document.querySelectorAll("#detail-title").length === 1 &&
        document.querySelectorAll("#episode-title").length === 1 &&
        document.querySelectorAll("#tech-title, #episode-tech-title").length <=
          2,
    ),
  );
  await shot(page, "episode-page");

  // 4. Previous in the season, next in the following season.
  const prevText = (
    await ep.locator(".adjacent .previous .target").textContent()
  ).trim();
  const nextText = (
    await ep.locator(".adjacent .next .target").textContent()
  ).trim();
  check(
    "neighbours_labels",
    prevText.endsWith(before.name) && nextText.startsWith(`S${s2.index} · `),
    `${prevText} / ${nextText}`,
  );
  await ep.locator(".adjacent .next").click();
  const nextName = nextText.split(" · ").slice(2).join(" · ");
  await waitEpisode(page, nextName);
  const nextPosition = (
    await episodePage(page).locator(".position").textContent()
  ).trim();
  check(
    "next_crosses_season",
    nextPosition.startsWith(`Saison ${s2.index} · `),
    `${nextName} — ${nextPosition}`,
  );
  await page.waitForTimeout(200);
  let focus = await activeInfo(page);
  check(
    "focus_on_next_button",
    focus.className.includes("next"),
    focus.className || focus.id,
  );
  await shot(page, "episode-next-season");
  await episodePage(page).locator(".adjacent .previous").click();
  await waitEpisode(page, last.name);
  await page.waitForTimeout(200);
  focus = await activeInfo(page);
  check(
    "previous_comes_back",
    focus.className.includes("previous"),
    focus.className,
  );

  // 5. Play from the page, Escape after the player keeps the page.
  await episodePage(page).locator(".play-actions button.play").click();
  const fromPage = await shortPlay(page);
  check("plays_from_page", fromPage.ok, fromPage.detail);
  check("engine_stopped_after_escape", fromPage.stopped);
  await waitEpisode(page, last.name);
  check("page_kept_after_player", (await episodeTitle(page).count()) === 1);

  // 6. Escape from the next season's episode: back on it, in its season.
  await episodePage(page).locator(".adjacent .next").click();
  await waitEpisode(page, nextName);
  await page.keyboard.press("Escape");
  await episodeTitle(page).waitFor({ state: "detached", timeout: 5000 });
  await page
    .waitForFunction(
      () => document.activeElement?.classList.contains("open"),
      null,
      { timeout: 20000 },
    )
    .catch(() => {});
  focus = await activeInfo(page);
  const focusedName = await page.evaluate(
    () => document.activeElement?.textContent.trim() ?? "",
  );
  check(
    "escape_back_on_episode",
    focus.className.includes("open") &&
      focusedName === nextName &&
      (await pressedSeason(page)) === s2.name,
    `${focusedName} (${await pressedSeason(page)})`,
  );
  check(
    "series_page_visible_again",
    await page.evaluate(() => {
      const s = document
        .querySelector("#detail-title")
        ?.closest("section.detail");
      return !!s && !s.inert && getComputedStyle(s).visibility === "visible";
    }),
  );
  await shot(page, "back-to-series");

  // 7. The thumbnail still plays, without opening the page.
  await page
    .locator(
      `ol.list li.episode[data-episode-id="${focus.episode}"] button.thumb`,
    )
    .click();
  const fromThumb = await shortPlay(page);
  check("thumb_still_plays", fromThumb.ok, fromThumb.detail);
  await detailTitle(page).waitFor({ timeout: 10000 });
  check("series_after_thumb_play", (await episodeTitle(page).count()) === 0);
  await page.waitForTimeout(1500);
  const left = [
    await resumeOf(page, last.id),
    await resumeOf(page, focus.episode),
  ];
  check(
    "no_resume_point_left",
    left.every((r) => r === null),
    JSON.stringify(left),
  );

  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  await signOut(page);
  check("signed_out", true);
} catch (e) {
  check("scenario_completed", false, e.message.split("\n")[0]);
  await shot(app.page, "failure").catch(() => {});
} finally {
  check("exit_code", (await app.close()) === 0);
  finish();
}
