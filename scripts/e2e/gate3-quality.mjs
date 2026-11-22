// Streaming quality setting on the real Emby test account, driven over
// CDP. No VIEWER_E2E_MAX_BITRATE: the cap comes from Réglages only.
// - « Qualité de lecture » › « Réseau local » set to 1,5 Mbit/s with the keyboard,
//   stored for the server and kept after a restart;
// - the first film then plays transcoded with the server's reason, and the info
//   panel shows the cap in effect;
// - back to « Maximale (automatique) »: the same film plays directly again.
// Short sessions, rewound to 0 before stopping: no resume point is left. The
// setting is put back to « Maximale » even on failure. Ends signed out.
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
const { check, shot, finish } = createReport("gate3-quality");
const LOW = 1_500_000;

const invoke = (page, cmd, args) =>
  page.evaluate(([c, a]) => window.__viewer.invoke(c, a), [cmd, args ?? {}]);
const command = (page, cmd) => invoke(page, "player_command", { command: cmd });
const settingsButton = (page) =>
  libraryNav(page).getByRole("button", { name: "Réglages", exact: true });
const section = (page) =>
  page.locator("section[aria-labelledby=settings-quality]");
const localSelect = (page) =>
  section(page).getByRole("combobox", { name: "Réseau local" });

async function openSettings(page) {
  await settingsButton(page).focus();
  await page.keyboard.press("Enter");
  await localSelect(page).waitFor({ timeout: 15000 });
}

/** Opens the first film from Films; returns its play button. */
async function firstFilm(page) {
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  await play.waitFor({ timeout: 15000 });
  return play;
}

/** Plays a few seconds, reads the badge and the cap line, rewinds and stops. */
async function playBriefly(page, play, name) {
  await play.focus();
  await page.keyboard.press("Enter");
  await playerBadge(page).waitFor({ timeout: 30000 });
  const badge = (await playerBadge(page).textContent()).trim();
  const first = await waitForPosition(page, 0.5, 45000);
  const second = await waitForPosition(page, (first?.timePos ?? 0) + 2, 20000);
  check(
    `${name}_advances`,
    second?.ok && second.timePos > (first?.timePos ?? 0),
    second?.ok
      ? `${first.timePos.toFixed(1)} → ${second.timePos.toFixed(1)} s · ${second.videoCodec} ${second.videoWidth}×${second.videoHeight}`
      : second?.error,
  );
  await page.keyboard.press("i");
  const info = page.getByRole("dialog", { name: "Informations de lecture" });
  await info.waitFor({ timeout: 5000 });
  const cap = await info
    .locator("dt", { hasText: "Débit maximal" })
    .locator("xpath=following-sibling::dd[1]")
    .textContent()
    .then((t) => t.trim());
  await shot(page, name);
  await page.keyboard.press("Escape");

  await command(page, { type: "seekAbsolute", seconds: 0 });
  await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    30000,
  );
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 15000 });
  const after = await diagnostics(page);
  check(`${name}_player_closed`, !after.ok, after.error ?? "still open");
  return { badge, cap };
}

let app = null;
try {
  app = await launch();
  let page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
  await openSettings(page);
  const initial = await invoke(page, "emby_playback_quality");
  check(
    "starts_automatic",
    initial.local === null && initial.remote === null && initial.localAddress,
    JSON.stringify(initial),
  );
  check(
    "choices_offered",
    (await localSelect(page).locator("option").allTextContents()).join(
      " | ",
    ) ===
      "Maximale (automatique) | 40 Mbit/s | 20 Mbit/s | 10 Mbit/s | 8 Mbit/s | 4 Mbit/s | 3 Mbit/s | 1,5 Mbit/s",
  );
  // Keyboard only: End picks the last choice of a focused closed select.
  await localSelect(page).focus();
  await page.keyboard.press("End");
  await page.waitForFunction(
    () =>
      window.__viewer
        .invoke("emby_playback_quality")
        .then((q) => q.local !== null),
    null,
    { timeout: 5000 },
  );
  const stored = await invoke(page, "emby_playback_quality");
  check("low_cap_stored", stored.local === LOW, JSON.stringify(stored));
  await shot(page, "settings-low");

  // A restart keeps it.
  check("exit_code_first", (await app.close()) === 0);
  app = await launch();
  page = app.page;
  await libraryNav(page).waitFor({ timeout: 30000 });
  await openSettings(page);
  check(
    "kept_after_restart",
    (await localSelect(page).inputValue()) === String(LOW),
    await localSelect(page).inputValue(),
  );

  const play = await firstFilm(page);
  const film = (await detailTitle(page).textContent()).trim();
  const low = await playBriefly(page, play, "capped");
  check(
    "capped_transcodes_with_reason",
    low.badge === "Transcodage · débit trop élevé",
    `${film} · ${low.badge}`,
  );
  check("capped_info_panel", low.cap === "1,5 Mbit/s · réseau local", low.cap);

  // Back to « Maximale »: the same film is no longer transcoded for its bitrate.
  await page.keyboard.press("Escape");
  await openSettings(page);
  await localSelect(page).focus();
  await page.keyboard.press("Home");
  await page.waitForFunction(
    () =>
      window.__viewer
        .invoke("emby_playback_quality")
        .then((q) => q.local === null),
    null,
    { timeout: 5000 },
  );
  check("back_to_automatic", true);
  const again = await firstFilm(page);
  const auto = await playBriefly(page, again, "automatic");
  check(
    "automatic_plays_directly",
    !auto.badge.startsWith("Transcodage"),
    auto.badge,
  );
  check(
    "automatic_info_panel",
    auto.cap === "Automatique · réseau local",
    auto.cap,
  );
  check(
    "no_resume_left",
    (await page.locator(".detail .status .resume").count()) === 0,
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
    await invoke(app.page, "emby_set_playback_quality", {
      local: null,
      remote: null,
    }).catch(() => {});
    await app.close().catch(() => {});
  }
  finish();
}
