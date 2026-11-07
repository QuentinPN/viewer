// Gate 3 skip intro and credits (ADR 0030) on the real Emby test account, driven
// over CDP:
// - the first episodes of the first series are looked up (emby_segments) until one
//   has segments; with the external sources off, nothing is found (no Emby marker
//   on this server);
// - played, its intro shows "Passer l'intro", still there once the controls fade;
//   Enter jumps to the intro's end and the button goes;
// - its credits show "Passer le générique" or "Épisode suivant" (not pressed:
//   skipping them would end the episode and mark it played);
// - with "Passer automatiquement", the intro is skipped without a key, once: back
//   into it, the button shows and nothing moves.
// Server side effects: none kept. Each playback is rewound to 0 before stopping, so
// no resume point is left. Read-only calls to IntroDB and TheIntroDB, with the
// provider ids of the looked up series (owner's agreement, ADR 0030).
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

const SERIES_TO_TRY = 12;
const SKIP_KEY = "viewer.player.skip";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-skip");

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
const skipButton = (page) => page.locator(".player .skip-main");
const text = async (locator) => (await locator.textContent())?.trim() ?? "";
const setSkip = (page, value) =>
  page.evaluate(
    ([k, v]) =>
      v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v),
    [SKIP_KEY, value],
  );

/** First episode of the season the series page opens on (`defaultSeason`). */
async function firstEpisode(page, seriesId) {
  const seasons = await call(page, "emby_seasons", { seriesId });
  const regular = seasons.filter((s) => s.index !== 0);
  const season =
    regular.find((s) => !s.played && (s.unplayedCount ?? 0) > 0) ??
    regular[0] ??
    seasons[0];
  if (!season) return null;
  const page0 = await call(page, "emby_episodes", {
    seriesId,
    seasonId: season.id,
    startIndex: 0,
    limit: 1,
  });
  return page0.items[0] ?? null;
}

/** Plays the episode row from the series page and waits for it to move. */
async function play(page, episodeId) {
  await page
    .locator(`li.episode[data-episode-id="${episodeId}"] button.thumb`)
    .click();
  await playerBadge(page).waitFor({ timeout: 20000 });
  return waitForPosition(page, 0.5, 20000);
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
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
}

let app = await launch();
let savedSkip = null;
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
  }
  await libraryNav(page).waitFor({ timeout: 20000 });
  savedSkip = await page.evaluate((k) => localStorage.getItem(k), SKIP_KEY);
  await setSkip(page, null);
  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Séries")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  const seriesIds = (
    await cards(page).evaluateAll((els) =>
      els.map((e) => e.getAttribute("data-item-id")),
    )
  ).slice(0, SERIES_TO_TRY);

  // 1. An episode with segments; an intro is preferred.
  let found = null;
  let looked = 0;
  for (const seriesId of seriesIds) {
    const episode = await firstEpisode(page, seriesId);
    if (!episode) continue;
    looked += 1;
    const segments = await call(page, "emby_segments", {
      itemId: episode.id,
      external: true,
    });
    if (segments.length === 0) continue;
    const local = await call(page, "emby_segments", {
      itemId: episode.id,
      external: false,
    });
    const candidate = { seriesId, episode, segments, local };
    found ??= candidate;
    if (segments.some((s) => s.kind === "intro")) {
      found = candidate;
      break;
    }
  }
  if (!found)
    throw new Error(`aucun segment sur ${looked} premiers épisodes de séries`);
  const { seriesId, episode, segments } = found;
  check(
    "segments_found",
    true,
    `${looked} épisode(s) cherché(s) ; ${segments
      .map(
        (s) =>
          `${s.kind} ${s.start.toFixed(1)}–${s.end?.toFixed(1) ?? "fin"} (${s.source})`,
      )
      .join(", ")}`,
  );
  check(
    "external_off_finds_nothing",
    found.local.length === 0,
    `${found.local.length} segment(s) Emby`,
  );
  const intro = segments.find((s) => s.kind === "intro");
  const credits = segments.find((s) => s.kind === "credits");

  await page.locator(`ul.grid button.card[data-item-id="${seriesId}"]`).click();
  await detailTitle(page).waitFor({ timeout: 20000 });

  // 2. The intro button, kept when the controls fade; Enter skips.
  const started = await play(page, episode.id);
  if (intro) {
    await command(page, {
      type: "seekAbsolute",
      seconds: intro.start + 1,
    });
    await skipButton(page).waitFor({ timeout: 10000 });
    check("intro_button", (await text(skipButton(page))) === "Passer l'intro");
    await page.waitForTimeout(3800);
    const faded = await page.locator(".player.hidden").count();
    check(
      "button_stays_when_controls_fade",
      faded === 1 && (await skipButton(page).isVisible()),
    );
    await shot(page, "intro-button");
    await page.keyboard.press("Enter");
    const after = await waitForDiagnostics(
      page,
      (d) => (d.timePos ?? 0) >= intro.end - 1.5,
      10000,
    );
    check(
      "enter_skips_intro",
      after?.ok && after.timePos >= intro.end - 1.5,
      `${after?.timePos?.toFixed(1)} s pour une fin à ${intro.end.toFixed(1)} s`,
    );
    await page.waitForTimeout(600);
    check("intro_button_gone", (await skipButton(page).count()) === 0);
  } else {
    check("intro_not_covered", true, "aucun épisode trouvé avec une intro");
  }

  // 3. The credits button: its label only.
  if (credits && started?.duration) {
    await command(page, {
      type: "seekAbsolute",
      seconds: credits.start + 1,
    });
    await skipButton(page).waitFor({ timeout: 10000 });
    const label = await text(skipButton(page));
    check(
      "credits_button",
      label === "Passer le générique" || label === "Épisode suivant",
      label,
    );
    await shot(page, "credits-button");
  }
  await rewindAndLeave(page, "manual");

  // 4. Auto-skip: once per playback.
  if (intro) {
    await setSkip(page, JSON.stringify({ external: true, auto: true }));
    await play(page, episode.id);
    // An intro at the very start is skipped as soon as the playback starts.
    if (intro.start > 3)
      await command(page, { type: "seekAbsolute", seconds: intro.start + 1 });
    const skipped = await waitForDiagnostics(
      page,
      (d) => (d.timePos ?? 0) >= intro.end - 1.5,
      10000,
    );
    check(
      "auto_skips_intro",
      skipped?.ok && skipped.timePos >= intro.end - 1.5,
      `${skipped?.timePos?.toFixed(1)} s`,
    );
    await command(page, { type: "seekAbsolute", seconds: intro.start + 1 });
    await skipButton(page).waitFor({ timeout: 10000 });
    await page.waitForTimeout(1500);
    const stay = await diagnostics(page);
    check(
      "auto_skips_once",
      stay.ok && stay.timePos < intro.end - 1.5,
      `${stay.timePos?.toFixed(1)} s, bouton affiché`,
    );
    await rewindAndLeave(page, "auto");
  }

  await setSkip(page, savedSkip);
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
    await setSkip(app.page, savedSkip).catch(() => {});
    await app.close().catch(() => {});
  }
  finish();
}
