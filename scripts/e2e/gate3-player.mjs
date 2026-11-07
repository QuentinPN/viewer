// Gate 3 final player (ADR 0017) on the real Emby test account, driven over CDP:
// - the title of a film, and series · season · episode above an episode's title;
// - the controls fade after 3 s without pointer or key, the cursor too, and come
//   back on a pointer move or a key; they stay while paused;
// - ±10 s buttons, remaining time;
// - mute (M), volume (↑/↓ and slider) applied to the engine and kept on this PC
//   for the next playback;
// - Audio, Sous-titres and diagnostic (I) panels; Escape closes a panel first,
//   then leaves the player;
// - fullscreen (F) back and forth.
// Starts two short playbacks rewound to 0 before stopping (no resume point is
// kept); the saved volume of this PC is restored at the end.
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
const { check, shot, finish } = createReport("gate3-player");
const VOLUME_KEY = "viewer.player.volume";

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
const resumeOf = async (page, id) =>
  (await call(page, "emby_item_detail", { itemId: id })).resume;
const chrome = (page) =>
  page.evaluate(() => {
    const p = document.querySelector(".player");
    const bottom = document.querySelector(".bottom.chrome");
    return {
      hidden: p?.classList.contains("hidden") ?? null,
      cursor: p ? getComputedStyle(p).cursor : null,
      opacity: bottom ? getComputedStyle(bottom).opacity : null,
    };
  });
const button = (page, name) =>
  page.locator(".player").getByRole("button", { name, exact: true });
const dialog = (page, name) => page.getByRole("dialog", { name });
const stored = (page) =>
  page.evaluate((k) => localStorage.getItem(k), VOLUME_KEY);
const waitVolume = (page, value) =>
  waitForDiagnostics(
    page,
    (d) => d.volume != null && Math.abs(d.volume - value) < 0.5,
    5000,
  );
const focusInside = (page, selector) =>
  page.evaluate((s) => !!document.activeElement?.closest(s), selector);

/** Title text, or the alt text of the logo that replaces it. */
const textOrAlt = (locator) =>
  locator.evaluate(
    (e) => e.textContent.trim() || e.querySelector("img")?.alt || "",
  );

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

let app = await launch();
let savedVolume = null;
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await libraryNav(page).waitFor({ timeout: 20000 });
  savedVolume = await stored(page);
  await page.evaluate((k) => localStorage.removeItem(k), VOLUME_KEY);

  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );

  // 1. A film without resume point, from its detail page.
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  let film = null;
  for (let n = 0; n < Math.min(10, await cards(page).count()); n++) {
    const id = await cards(page).nth(n).getAttribute("data-item-id");
    if ((await resumeOf(page, id)) === null) {
      film = { n, id };
      break;
    }
  }
  check("film_found", film !== null, film && `carte ${film.n + 1}`);
  if (!film) throw new Error("aucun film sans point de reprise");
  await cards(page).nth(film.n).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  const filmName = await textOrAlt(detailTitle(page));
  await page.locator(".detail button.play").first().click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  const started = await waitForPosition(page, 1, 20000);
  check("film_plays", started?.ok && started.timePos > 1);

  const h1 = (await page.locator(".player .heading h1").textContent()).trim();
  check(
    "film_title",
    h1 === filmName && (await page.locator(".player .kicker").count()) === 0,
    h1,
  );
  const decision = (await playerBadge(page).textContent()).trim();
  check(
    "decision_shown",
    /^(Lecture directe|Flux direct|Transcodage)/.test(decision),
    decision,
  );
  const remaining = (
    await page.locator(".player .timeline .time").last().textContent()
  ).trim();
  check("remaining_time", /^−\d+:\d\d/.test(remaining), remaining);
  await shot(page, "film-controls");

  // 2. Controls fade after 3 s without activity, and come back.
  await page.mouse.move(400, 300);
  await page.waitForTimeout(3800);
  const faded = await chrome(page);
  check(
    "controls_fade",
    faded.hidden === true && faded.opacity === "0" && faded.cursor === "none",
    JSON.stringify(faded),
  );
  await shot(page, "controls-hidden");
  // The caption buttons still take clicks: they never fall through to the video,
  // which would pause it instead of minimizing.
  const caption = await page.evaluate(() =>
    [...document.querySelectorAll(".titlebar button")].map((b) => {
      const r = b.getBoundingClientRect();
      const hit = document.elementFromPoint(
        r.x + r.width / 2,
        r.y + r.height / 2,
      );
      return b.contains(hit);
    }),
  );
  check(
    "caption_buttons_take_clicks_when_faded",
    caption.length === 3 && caption.every(Boolean),
    JSON.stringify(caption),
  );
  await page.mouse.move(420, 320);
  await page.waitForTimeout(400);
  const moved = await chrome(page);
  check(
    "pointer_brings_back",
    moved.hidden === false && moved.opacity === "1",
    JSON.stringify(moved),
  );
  await page.waitForTimeout(3800);
  await page.keyboard.press("Shift");
  await page.waitForTimeout(400);
  check("key_brings_back", (await chrome(page)).hidden === false);

  // 3. Paused, the controls stay.
  await page.keyboard.press("Space");
  const paused = await waitForDiagnostics(page, (d) => d.paused === true, 5000);
  await page.waitForTimeout(3800);
  check(
    "paused_keeps_controls",
    paused?.paused === true && (await chrome(page)).hidden === false,
  );
  await page.keyboard.press("Space");
  const resumed = await waitForDiagnostics(
    page,
    (d) => d.paused === false,
    5000,
  );
  check("space_resumes", resumed?.paused === false);

  // 4. ±10 s.
  const before = (await diagnostics(page)).timePos;
  await button(page, "Avancer de 10 secondes").click();
  const ahead = await waitForDiagnostics(
    page,
    (d) => d.timePos >= before + 8,
    10000,
  );
  check(
    "forward_10",
    ahead?.ok && ahead.timePos < before + 15,
    `${before?.toFixed(1)} → ${ahead?.timePos?.toFixed(1)} s`,
  );
  await button(page, "Reculer de 10 secondes").click();
  const back = await waitForDiagnostics(
    page,
    (d) => d.timePos < ahead.timePos - 7,
    10000,
  );
  check(
    "rewind_10",
    back?.ok,
    `${ahead?.timePos?.toFixed(1)} → ${back?.timePos?.toFixed(1)} s`,
  );

  // 5. Volume: default 100, mute, arrows, slider; applied to the engine and kept.
  const initial = await waitVolume(page, 100);
  check("volume_default_100", initial?.ok, initial?.volume);
  await page.keyboard.press("m");
  const muted = await waitVolume(page, 0);
  check(
    "m_mutes",
    muted?.ok &&
      (await button(page, "Rétablir le son").count()) === 1 &&
      JSON.parse(await stored(page)).muted === true,
    `${muted?.volume} · ${await stored(page)}`,
  );
  await page.keyboard.press("m");
  check("m_unmutes", (await waitVolume(page, 100))?.ok);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  const lowered = await waitVolume(page, 90);
  check("arrows_lower", lowered?.ok, lowered?.volume);
  await page.locator(".player input.level").fill("40");
  const slid = await waitVolume(page, 40);
  check(
    "slider_sets_and_saves",
    slid?.ok && JSON.parse(await stored(page)).level === 40,
    `${slid?.volume} · ${await stored(page)}`,
  );

  // 6. Panels.
  const tracks = await call(page, "player_tracks");
  const audio = tracks.filter((t) => t.kind === "audio");
  const subs = tracks.filter((t) => t.kind === "sub");
  await button(page, "Audio").click();
  await dialog(page, "Audio").waitFor({ timeout: 3000 });
  check(
    "audio_panel",
    (await dialog(page, "Audio").locator('input[type="radio"]').count()) ===
      audio.length && (await focusInside(page, '[role="dialog"]')),
    (await dialog(page, "Audio").locator("label").allTextContents())
      .map((t) => t.trim())
      .join(" | "),
  );
  await shot(page, "audio-panel");
  await page.keyboard.press("Escape");
  await dialog(page, "Audio").waitFor({ state: "detached", timeout: 3000 });
  check(
    "escape_closes_panel_only",
    (await playerBadge(page).count()) === 1 &&
      (await page.evaluate(
        () => document.activeElement?.getAttribute("aria-label") === "Audio",
      )),
  );

  await button(page, "Sous-titres").click();
  const subsDialog = dialog(page, "Sous-titres");
  await subsDialog.waitFor({ timeout: 3000 });
  const subLabels = (await subsDialog.locator("label").allTextContents()).map(
    (t) => t.trim(),
  );
  check(
    "subtitles_panel",
    subLabels[0] === "Désactivés" && subLabels.length === subs.length + 1,
    subLabels.join(" | "),
  );
  if (subs.length > 0) {
    const original = subs.find((s) => s.selected)?.id ?? null;
    // Pick another choice, check the engine, then put it back.
    const other = original === null ? 1 : 0;
    await subsDialog.locator("label").nth(other).click();
    const wanted = other === 0 ? null : subs[0].id;
    const deadline = Date.now() + 5000;
    let now;
    do {
      now =
        (await call(page, "player_tracks")).find(
          (t) => t.kind === "sub" && t.selected,
        )?.id ?? null;
      if (now === wanted) break;
      await page.waitForTimeout(200);
    } while (Date.now() < deadline);
    check("subtitle_switch", now === wanted, `${original} → ${now}`);
    const index =
      original === null ? 0 : subs.findIndex((s) => s.id === original) + 1;
    await subsDialog.locator("label").nth(index).click();
  } else {
    check("subtitle_switch", true, "aucun sous-titre sur ce film");
  }
  await shot(page, "subtitles-panel");
  await page.keyboard.press("Escape");
  await subsDialog.waitFor({ state: "detached", timeout: 3000 });

  await page.keyboard.press("i");
  const info = dialog(page, "Informations de lecture");
  await info.waitFor({ timeout: 3000 });
  await page.waitForTimeout(1200);
  const facts = await info.locator("dt").allTextContents();
  check(
    "info_panel",
    facts.includes("Décodage") && facts.includes("Serveur"),
    (await info.locator("dd").allTextContents()).join(" | "),
  );
  await shot(page, "info-panel");
  await page.keyboard.press("i");
  await info.waitFor({ state: "detached", timeout: 3000 });

  // 7. Fullscreen back and forth, covering the whole monitor, from a restored
  // and from a maximized window (tao kept the work area there, leaving the
  // taskbar visible), which is maximized again on the way out.
  const coversScreen = () =>
    page.evaluate(async () => {
      await new Promise((r) => setTimeout(r, 800));
      return {
        ok: innerWidth === screen.width && innerHeight === screen.height,
        size: `${innerWidth}x${innerHeight} / ${screen.width}x${screen.height}`,
      };
    });
  const windowCall = (cmd) =>
    page.evaluate(
      (cmd) =>
        window.__TAURI_INTERNALS__.invoke(`plugin:window|${cmd}`, {
          label: "main",
        }),
      cmd,
    );
  await page.keyboard.press("f");
  await button(page, "Quitter le plein écran").waitFor({ timeout: 5000 });
  const restoredFull = await coversScreen();
  check("fullscreen_covers_screen", restoredFull.ok, restoredFull.size);
  await page.keyboard.press("f");
  await button(page, "Plein écran").waitFor({ timeout: 5000 });
  check("fullscreen_toggles", true);
  await windowCall("toggle_maximize");
  await page.waitForTimeout(800);
  await page.keyboard.press("f");
  await button(page, "Quitter le plein écran").waitFor({ timeout: 5000 });
  const maximizedFull = await coversScreen();
  check(
    "fullscreen_from_maximized_covers_screen",
    maximizedFull.ok,
    maximizedFull.size,
  );
  await page.keyboard.press("f");
  await button(page, "Plein écran").waitFor({ timeout: 5000 });
  await page.waitForTimeout(800);
  check("maximized_after_fullscreen", await windowCall("is_maximized"));
  await windowCall("toggle_maximize");
  await page.waitForTimeout(800);

  // 8. Escape closes an open panel, then leaves; back on the film page.
  await button(page, "Audio").click();
  await dialog(page, "Audio").waitFor({ timeout: 3000 });
  await page.keyboard.press("Escape");
  await dialog(page, "Audio").waitFor({ state: "detached", timeout: 3000 });
  await rewindAndLeave(page, "film");
  await detailTitle(page).waitFor({ timeout: 10000 });
  check("back_on_film_page", true);

  // 9. The volume is kept for the next playback.
  await page.locator(".detail button.play").first().click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  await waitForPosition(page, 1, 20000);
  const kept = await waitVolume(page, 40);
  check("volume_kept", kept?.ok, kept?.volume);
  await rewindAndLeave(page, "film_again");

  // 10. An episode: series · season · episode above its title.
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
  await libraries.nth(kinds.indexOf("Séries")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  const row = page
    .locator("ol.list li.episode:not(.skeleton)", {
      has: page.locator('button.thumb[aria-label^="Lire "]'),
    })
    .first();
  await row.waitFor({ timeout: 20000 });
  const episodeId = await row.getAttribute("data-episode-id");
  const episodeName = (await row.locator("button.open").textContent()).trim();
  await row.locator("button.thumb").click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  await waitForPosition(page, 1, 20000);
  const kicker = (await page.locator(".player .kicker").textContent()).trim();
  const episodeH1 = (
    await page.locator(".player .heading h1").textContent()
  ).trim();
  check(
    "episode_heading",
    / · (S\d+ · É\d+|Spécial)/.test(kicker) && episodeH1 === episodeName,
    `${kicker} — ${episodeH1}`,
  );
  await shot(page, "episode-controls");
  await rewindAndLeave(page, "episode");

  // 11. Nothing kept on the server.
  await page.waitForTimeout(1500);
  const left = [await resumeOf(page, film.id), await resumeOf(page, episodeId)];
  check(
    "no_resume_point_left",
    left.every((r) => r === null),
    JSON.stringify(left),
  );

  await page.evaluate(
    ([k, v]) =>
      v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v),
    [VOLUME_KEY, savedVolume],
  );
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
  if (app) {
    await app.page
      .evaluate(
        ([k, v]) =>
          v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v),
        [VOLUME_KEY, savedVolume],
      )
      .catch(() => {});
    await app.close().catch(() => {});
  }
  finish();
}
