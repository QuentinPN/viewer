// Gate 2 HLS transcoding scenario on the real Emby test account, driven over CDP.
// The debug build caps the streaming bitrate at 3 Mbit/s through
// VIEWER_E2E_MAX_BITRATE, so the server must transcode the first film:
// - the player badge shows the transcode and its reason in French;
// - playback advances, the server's external text subtitles appear in the menu;
// - a seek far into the film lands there (Emby restarts the encoder at the offset);
// - back to 0 then Escape: the stop report ends the encoding and leaves no resume
//   point, the detail page offers "Lecture" again.
// Short session (authorized 2026-10-28). Starts in any state, ends signed out.
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
const { check, shot, finish } = createReport("gate2-transcode");

const command = (page, cmd) =>
  page.evaluate(
    (c) => window.__viewer.invoke("player_command", { command: c }),
    cmd,
  );

const SEEK_TARGET = 600;

let app = await launch({ env: { VIEWER_E2E_MAX_BITRATE: "3000000" } });
try {
  const page = app.page;
  if ((await startScreen(page)) === "signedOut") {
    await signIn(page, account, account.password);
    await libraryNav(page).waitFor({ timeout: 20000 });
  }
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
  check(
    "film_not_started",
    (await play.textContent()).trim() === "Lecture",
    (await detailTitle(page).textContent()).trim(),
  );

  await play.focus();
  const started = Date.now();
  await page.keyboard.press("Enter");
  await playerBadge(page).waitFor({ timeout: 20000 });
  const method = (await playerBadge(page).textContent()).trim();
  check(
    "badge_transcode_reason",
    method === "Transcodage · débit trop élevé",
    method,
  );

  const first = await waitForPosition(page, 0.5, 30000);
  const second = await waitForPosition(page, (first?.timePos ?? 0) + 2);
  check(
    "playback_advances",
    second?.ok && second.timePos > (first?.timePos ?? 0),
    second?.ok
      ? `${first.timePos.toFixed(1)} → ${second.timePos.toFixed(1)} s après ${((Date.now() - started) / 1000).toFixed(1)} s · ${second.videoCodec} ${second.videoWidth}×${second.videoHeight} · ${second.container}`
      : second?.error,
  );

  // External subtitles: Emby extracts them from the file, which may take a while.
  // They are listed in the "Sous-titres" panel of the player.
  const subsButton = page.getByRole("button", {
    name: "Sous-titres",
    exact: true,
  });
  await subsButton.click();
  const subsPanel = page.getByRole("dialog", { name: "Sous-titres" });
  const external = subsPanel
    .locator("label.choice")
    .filter({ hasText: "(externe)" });
  await external
    .first()
    .waitFor({ state: "attached", timeout: 45000 })
    .catch(() => {});
  const labels = (await external.allTextContents()).map((t) => t.trim());
  const selected = await subsPanel
    .locator("label.choice", { has: page.locator("input:checked") })
    .textContent()
    .then((t) => t.trim())
    .catch(() => "");
  check(
    "external_subtitles_listed",
    labels.length > 0,
    `${labels.join(" | ")} · choisi : ${selected}`,
  );
  await shot(page, "transcoding");
  await subsButton.click();

  await command(page, { type: "seekAbsolute", seconds: SEEK_TARGET });
  const seekStart = Date.now();
  const there = await waitForPosition(page, SEEK_TARGET + 1, 30000);
  check(
    "seek_far_lands_there",
    there?.ok &&
      there.timePos >= SEEK_TARGET &&
      there.timePos < SEEK_TARGET + 30,
    there?.ok
      ? `${there.timePos.toFixed(1)} s en ${((Date.now() - seekStart) / 1000).toFixed(1)} s`
      : there?.error,
  );

  // Back to 0 so the stop report leaves no resume point.
  await command(page, { type: "seekAbsolute", seconds: 0 });
  const rewound = await waitForDiagnostics(
    page,
    (d) => (d.timePos ?? 99) < 3 && (d.timePos ?? 0) > 0,
    30000,
  );
  check("rewound", rewound?.ok && rewound.timePos < 3, rewound?.timePos);

  await page.keyboard.press("Escape");
  await detailTitle(page).waitFor({ state: "visible", timeout: 10000 });
  check(
    "session_length",
    true,
    `${((Date.now() - started) / 1000).toFixed(0)} s de lecture`,
  );
  const after = await diagnostics(page);
  check("player_closed", !after.ok, after.error ?? "still open");
  await play.filter({ hasText: "Lecture" }).waitFor({ timeout: 15000 });
  check(
    "no_resume_left",
    (await page.locator(".detail .status .resume").count()) === 0,
  );
  await shot(page, "back");

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
  finish();
}
