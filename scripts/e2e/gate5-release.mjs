// Gate 5 smoke test of the installed release build (ADR 0028), on the real Emby test
// account, driven over CDP. The release build ignores every VIEWER_* variable, so its
// window shows on screen and its sound is only turned down through the player:
// - the bundled frontend loads, not the Vite server, and the window is not moved off-screen;
// - the Gate 1 prototype is refused;
// - the bitrate cap of the scenarios is ignored: no "débit trop élevé" transcode;
// - a film plays, a seek lands, back to 0 then Escape leaves no resume point.
// VIEWER_RELEASE_EXE sets the executable, by default the per-user install. The installed
// app holds the owner's data (ADR 0105): a session of another user is left untouched
// and the scenario stops; at the end the test account is signed out and its tile
// forgotten, so the owner's shelf keeps only their own servers.
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
  waitForDiagnostics,
  waitForPosition,
} from "./scenario.mjs";

const account = testAccount();
const { check, shot, finish } = createReport("gate5-release");
const exe =
  process.env.VIEWER_RELEASE_EXE ??
  join(process.env.LOCALAPPDATA, "Viewer", "viewer.exe");
const SEEK_TARGET = 600;

const invoke = (page, cmd, args) =>
  page.evaluate(
    ([c, a]) => window.__TAURI_INTERNALS__.invoke(c, a),
    [cmd, args],
  );
const command = (page, c) => invoke(page, "player_command", { command: c });

let app = await launch({
  exe,
  env: { VIEWER_E2E_MAX_BITRATE: "3000000" },
  timeoutMs: 45000,
});
try {
  const page = app.page;
  const origin = new URL(page.url()).origin;
  check("bundled_frontend", !origin.includes(":1420"), origin);
  const screenX = await page.evaluate(() => window.screenX);
  check("window_on_screen", screenX > -10000, `x = ${screenX}`);

  const prototype = await invoke(page, "player_open", {
    request: { source: "https://example.invalid/a.mkv" },
  }).then(
    () => "accepted",
    (e) => (typeof e === "string" ? e : (e?.message ?? JSON.stringify(e))),
  );
  check(
    "prototype_refused",
    prototype.includes("prototype indisponible"),
    prototype,
  );

  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  } else {
    const label = await page
      .getByRole("button", { name: /^Compte : / })
      .getAttribute("aria-label");
    if (label !== `Compte : ${account.user}`)
      throw new Error(
        `the installed app is signed in as another user (${label}): left untouched`,
      );
  }
  check("signed_in", true);
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  await libraries.nth(kinds.indexOf("Films")).click();
  await cards(page).first().waitFor({ timeout: 20000 });
  await cards(page).first().click();
  const play = page.locator(".detail button.play");
  const fromStart = page.locator(".detail button.from-start");
  await play.waitFor({ timeout: 15000 });
  // The heading holds the film's logo when it has one, its name otherwise.
  const title = await detailTitle(page).evaluate(
    (h) => h.querySelector("img")?.alt ?? h.textContent.trim(),
  );

  const started = Date.now();
  await ((await fromStart.count()) ? fromStart : play).click();
  await playerBadge(page).waitFor({ timeout: 30000 });
  await command(page, { type: "setVolume", percent: 0 }).catch(() => {});
  const badge = (await playerBadge(page).textContent()).trim();
  check("bitrate_cap_ignored", !badge.includes("débit trop élevé"), badge);

  const first = await waitForPosition(page, 1, 45000);
  await command(page, { type: "setVolume", percent: 0 }).catch(() => {});
  const second = await waitForPosition(page, (first?.timePos ?? 0) + 3);
  check(
    "playback_advances",
    second?.ok && second.timePos > (first?.timePos ?? 0),
    second?.ok
      ? `${title} · ${first.timePos.toFixed(1)} → ${second.timePos.toFixed(1)} s, première image après ${((Date.now() - started) / 1000).toFixed(1)} s · ${second.videoCodec} ${second.videoWidth}×${second.videoHeight} · ${second.container}${second.hwdec ? ` · ${second.hwdec}` : ""}`
      : second?.error,
  );
  await shot(page, "playing");

  await command(page, { type: "seekAbsolute", seconds: SEEK_TARGET });
  const seekStart = Date.now();
  const there = await waitForPosition(page, SEEK_TARGET + 1, 30000);
  check(
    "seek_lands_there",
    there?.ok &&
      there.timePos >= SEEK_TARGET &&
      there.timePos < SEEK_TARGET + 30,
    there?.ok
      ? `${there.timePos.toFixed(1)} s en ${((Date.now() - seekStart) / 1000).toFixed(1)} s`
      : there?.error,
  );

  // Back to 0 so the stop report leaves no resume point.
  await command(page, { type: "seekAbsolute", seconds: 0 });
  await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    30000,
  );
  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });
  check(
    "no_resume_left",
    (await page.locator(".detail .status .resume").count()) === 0,
  );

  await page.keyboard.press("Escape");
  await signOut(page);
  const shelf = await invoke(page, "emby_shelf", {});
  for (const { server } of shelf.filter(
    (s) => s.server.userName === account.user,
  ))
    await invoke(page, "emby_forget_server", {
      serverId: server.id,
      userId: server.userId,
    });
  check(
    "test_tile_forgotten",
    !(await invoke(page, "emby_shelf", {})).some(
      (s) => s.server.userName === account.user,
    ),
  );
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
