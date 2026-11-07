// Audio and subtitle preferences (ADR 0035) on the real Emby test account, driven
// over CDP:
// - Réglages › Audio et sous-titres: English audio, French subtitles always, very
//   large yellow text on a dark box, raised; the choice is kept in localStorage;
// - the film « 2 Coeurs » (30207: French and English audio, French forced and full
//   subtitles, English ones) then plays with the English audio and the full French
//   subtitles, not the server's picks; mpv reports the style options;
// - back to the defaults, the same film plays the server's picks again (French
//   audio, the default forced subtitle) with mpv's default look.
// Each playback is short and rewound to 0 before stopping: no resume point is left.
// The preferences are restored. Starts in any state, ends signed out.
import { launch } from "./app.mjs";
import {
  playerBadge as badge,
  createReport,
  detailTitle,
  diagnostics,
  homeHeading,
  libraryNav,
  signIn,
  signOut,
  startScreen,
  testAccount,
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate3-subtitles");

const FILM = { id: "30207", query: "2 Coeurs" };
const KEY = "viewer.player.subtitles";
/** mpv reports `fr` for the `fre` of the file. */
const FRENCH = ["fr", "fre", "fra"];

const invoke = (page, cmd, args) =>
  page.evaluate(
    ([c, a]) => window.__TAURI_INTERNALS__.invoke(c, a),
    [cmd, args],
  );
const section = (page) =>
  page.locator("section[aria-labelledby=settings-subtitles]");
/** The list under `label`; its accessible name also holds the chosen option. */
const field = (page, label) =>
  section(page)
    .locator("label.field")
    .filter({
      has: page.locator("span", { hasText: new RegExp(`^${label}$`) }),
    })
    .locator("select");
const choose = (page, label, value) => field(page, label).selectOption(value);

/** Opens the film from the search, plays it from the start, returns the tracks. */
async function playFilm(page, label) {
  const field = page.locator("#search-field");
  await field.fill(FILM.query);
  const card = page.locator(`button.card[data-item-id="${FILM.id}"]`).first();
  await card.waitFor({ timeout: 15000 });
  await card.click();
  await detailTitle(page).waitFor({ timeout: 15000 });
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  const text = (await play.textContent()).trim();
  // A resume point is the owner's: never rewind it.
  check(`${label}_not_started`, text === "Lecture", text);
  if (text !== "Lecture") throw new Error("film already started");
  await play.click();
  await badge(page).waitFor({ timeout: 20000 });
  const method = (await badge(page).textContent()).trim();
  check(`${label}_direct_play`, method.startsWith("Lecture directe"), method);
  await waitForPosition(page, 1, 30000);
  const tracks = await invoke(page, "player_tracks");
  const audio = tracks.find((t) => t.kind === "audio" && t.selected);
  const sub = tracks.find((t) => t.kind === "sub" && t.selected);
  const describe = (t) =>
    t
      ? `${t.lang ?? "?"} ${t.codec ?? ""}${t.default ? " (défaut)" : ""}`
      : "aucun";
  const all = tracks
    .filter((t) => t.kind !== "video")
    .map((t) => `${t.kind}${t.id}:${describe(t)}`)
    .join(", ");
  const diag = await diagnostics(page);
  const options = Object.fromEntries(diag.subtitleOptions ?? []);
  return { audio, sub, describe, all, options };
}

/** Rewinds to 0, leaves the player and the detail page. */
async function stop(page, label) {
  await invoke(page, "player_command", {
    command: { type: "seekAbsolute", seconds: 0 },
  });
  await waitForDiagnostics(page, (d) => (d.timePos ?? 99) < 1, 10000);
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  const after = await diagnostics(page);
  check(`${label}_player_closed`, !after.ok, after.error ?? "still open");
  await page
    .locator(".detail button.play")
    .filter({ hasText: "Lecture" })
    .waitFor({ timeout: 15000 });
  check(
    `${label}_no_resume_left`,
    (await page.locator(".detail .status .resume").count()) === 0,
  );
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "detached", timeout: 5000 });
}

const restore = (page) =>
  page.evaluate(
    ([k, v]) =>
      v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v),
    [KEY, saved],
  );

let app = await launch();
let saved = null;
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
  await homeHeading(page).waitFor({ timeout: 20000 });
  saved = await page.evaluate((k) => localStorage.getItem(k), KEY);
  await page.evaluate((k) => localStorage.removeItem(k), KEY);

  // Réglages: the section and its choices.
  await libraryNav(page)
    .getByRole("button", { name: "Réglages", exact: true })
    .click();
  await section(page).waitFor({ timeout: 10000 });
  check(
    "subtitle_language_hidden_by_default",
    (await field(page, "Langue des sous-titres").count()) === 0,
  );
  await choose(page, "Langue audio", "eng");
  await choose(page, "Sous-titres", "always");
  await choose(page, "Langue des sous-titres", "fre");
  await choose(page, "Taille", "larger");
  await choose(page, "Couleur", "yellow");
  await choose(page, "Lisibilité", "box");
  await choose(page, "Position", "raised");
  await shot(page, "settings");
  const stored = JSON.parse(
    await page.evaluate((k) => localStorage.getItem(k), KEY),
  );
  check(
    "preferences_stored",
    stored.tracks.audioLanguage === "eng" &&
      stored.tracks.subtitleMode === "always" &&
      stored.tracks.subtitleLanguage === "fre" &&
      stored.subtitleStyle.size === "larger" &&
      stored.subtitleStyle.position === "raised",
    JSON.stringify(stored),
  );

  // With the preferences: English audio, the full French subtitles, the style.
  const chosen = await playFilm(page, "preferences");
  check(
    "preferences_audio_english",
    ["en", "eng"].includes(chosen.audio?.lang),
    `${chosen.describe(chosen.audio)} · ${chosen.all}`,
  );
  check(
    "preferences_full_french_subtitles",
    FRENCH.includes(chosen.sub?.lang) && !chosen.sub.default,
    chosen.describe(chosen.sub),
  );
  check(
    "preferences_style_in_mpv",
    chosen.options["sub-scale"] === "1.500000" &&
      chosen.options["sub-color"] === "#FFFFE14D" &&
      chosen.options["sub-border-style"] === "background-box" &&
      chosen.options["sub-pos"] === "85.000000" &&
      chosen.options["sub-ass-override"] === "no",
    JSON.stringify(chosen.options),
  );
  await shot(page, "preferences-playing");
  await stop(page, "preferences");

  // Back to the defaults: the server's picks and mpv's look.
  await page.evaluate((k) => localStorage.removeItem(k), KEY);
  const server = await playFilm(page, "defaults");
  check(
    "defaults_audio_french",
    FRENCH.includes(server.audio?.lang),
    `${server.describe(server.audio)} · ${server.all}`,
  );
  check(
    "defaults_server_subtitle",
    FRENCH.includes(server.sub?.lang) && server.sub.default,
    server.describe(server.sub),
  );
  check(
    "defaults_style_in_mpv",
    server.options["sub-scale"] === "1.000000" &&
      server.options["sub-color"] === "#FFFFFFFF" &&
      server.options["sub-border-style"] === "outline-and-shadow" &&
      server.options["sub-pos"] === "100.000000",
    JSON.stringify(server.options),
  );
  await stop(page, "defaults");

  await restore(page);
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
    await restore(app.page).catch(() => {});
    await app.close().catch(() => {});
  }
  finish();
}
