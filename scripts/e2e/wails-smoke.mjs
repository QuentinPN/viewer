// Smoke test of the Go + Wails debug build on the real Emby server, driven over
// CDP like the other scenarios (scripted UI tests never take over the desktop): no OS input, no screen capture. Keys go to the page through
// Input.dispatchKeyEvent; the HTML is captured with Page.captureScreenshot and the
// window itself, mpv video included, with PrintWindow on the app's own HWND
// (print-window.ps1).
//
// Steps: first-run screen → manual sign-in with the demo account → home artwork →
// Films grid and a film page → playback (time-pos, hwdec, tracks from the app's
// own player events and commands, PrintWindow) → audio and subtitle switch from
// the player panels → pause and seek by keys → stop and Emby resume point → the
// Demander tab (read-only) → window closed during playback, exit time → restart,
// session restored, resume point written at close → resume point cleared (rewind
// to 0, as gate3-player) → sign-out, no vault entry left.
//
// Read-only on the server apart from normal playback reporting: no setting, no
// watched or favourite toggle, no Request request. The demo account comes from
// debug.env (EMBY_IP, LOGIN, PASSWORD, VIEWER_UR_URL) or the environment; its
// values never reach the console, report.json or a screenshot (masked on screen).
//
// The app runs with APPDATA and LOCALAPPDATA in the evidence folder: a real first
// run, and the owner's debug data stay untouched. The folder is deleted at the end;
// the Windows vault is shared, so the run must end signed out.
//
//   node scripts/e2e/wails-smoke.mjs [--build] [--offline] [--out <dir>]
//
// --build    npm run build, then a debug `go build` of go-app/cmd/viewer
// --offline  first-run screen and exit only: no server is contacted by the script
// --out      evidence folder (default: <tmp>/viewer-wails-smoke-<timestamp>)
// --film     id of the film to play (on the first page of the Films grid), even
//            with a resume point; default: the first film without one
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildDebug, debugExe as exe, launch, root } from "./app.mjs";
import {
  cards,
  debugEnv,
  detailTitle,
  homeHeading,
  libraryNav,
  playerBadge,
  signIn,
  signInHeading,
  signOut,
  startScreen,
} from "./scenario.mjs";

const argv = process.argv.slice(2);
const OFFLINE = argv.includes("--offline");
const outArg = argv.indexOf("--out");
const filmArg = argv.indexOf("--film");
const stamp = new Date()
  .toISOString()
  .replace(/[-:]/g, "")
  .replace("T", "-")
  .slice(0, 15);
const out =
  outArg >= 0 ? argv[outArg + 1] : join(tmpdir(), `viewer-wails-smoke-${stamp}`);
const PORT = 9341;
const INVOKE = "viewer/internal/bridge.Bridge.Invoke";
const EMBY_IMG = "http://wails.localhost/emby-img/";
const UR_IMG = "http://wails.localhost/requestimg/";
/** Brief of the smoke run; MAP.md allows 6 s. */
const EXIT_LIMIT_MS = 3000;

mkdirSync(out, { recursive: true });
const profile = join(out, "profile");
const appEnv = {
  APPDATA: join(profile, "Roaming"),
  LOCALAPPDATA: join(profile, "Local"),
  VIEWER_E2E_LOG_DIR: join(out, "logs"),
};

// ---- account and redaction ------------------------------------------------

const account = OFFLINE
  ? null
  : {
      url: `http://${debugEnv("EMBY_IP")}`,
      user: debugEnv("LOGIN"),
      password: debugEnv("PASSWORD"),
      ur: debugEnv("VIEWER_UR_URL"),
    };
// Read once: never passed on to the app process.
for (const key of ["EMBY_IP", "LOGIN", "PASSWORD", "VIEWER_UR_URL"])
  delete process.env[key];
const secrets = account
  ? [
      account.password,
      account.url,
      new URL(account.url).host,
      new URL(account.url).hostname,
      account.ur,
      new URL(account.ur).host,
      new URL(account.ur).hostname,
      account.user,
    ].filter((s) => s && s.length >= 3)
  : [];
/** Anything printed or written goes through this first. */
const scrub = (value) =>
  secrets
    .sort((a, b) => b.length - a.length)
    .reduce((t, s) => t.split(s).join("«masqué»"), String(value));

// ---- report -----------------------------------------------------------------

const checks = [];
const report = {
  run: OFFLINE ? "offline" : "real-server",
  startedAt: new Date().toISOString(),
  build: "debug (io.github.quentinpn.viewer.debug, coffre Viewer-debug)",
  checks,
  facts: {},
};
let shots = 0;
function check(name, passed, detail = "") {
  const d = scrub(detail);
  checks.push({ name, passed: Boolean(passed), detail: d });
  console.log(`${passed ? "PASS" : "FAIL"} ${name}${d ? ` — ${d}` : ""}`);
}
function fact(name, value) {
  report.facts[name] = JSON.parse(scrub(JSON.stringify(value ?? null)));
}
/** CDP screenshot of the page, server addresses and login masked on screen. */
async function shot(page, name) {
  const file = join(out, `${String(shots++).padStart(2, "0")}-${name}.png`);
  await page.evaluate((hide) => {
    const has = (t) => hide.some((h) => t?.includes(h));
    window.__masked = [];
    for (const input of document.querySelectorAll("input")) {
      if (!has(input.value)) continue;
      window.__masked.push(() =>
        input.style.removeProperty("-webkit-text-security"),
      );
      input.style.setProperty("-webkit-text-security", "disc");
    }
    const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) {
      if (!has(n.nodeValue)) continue;
      const node = n;
      const text = node.nodeValue;
      window.__masked.push(() => {
        node.nodeValue = text;
      });
      node.nodeValue = hide.reduce((t, h) => t.split(h).join("•••"), text);
    }
  }, secrets);
  try {
    await page.screenshot({ path: file });
  } finally {
    await page.evaluate(() => {
      for (const undo of window.__masked ?? []) undo();
      window.__masked = [];
    });
  }
  return file;
}

/** PrintWindow of the app's own window (video included); stats, never pixels. */
function printWindow(pid, name) {
  const file = join(out, `${String(shots++).padStart(2, "0")}-${name}.png`);
  const r = spawnSync(
    "powershell",
    [
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-File",
      join(root, "scripts", "e2e", "print-window.ps1"),
      "-ProcessId",
      String(pid),
      "-Path",
      file,
    ],
    { encoding: "utf8", windowsHide: true },
  );
  if (r.status !== 0) throw new Error(`PrintWindow: ${r.stderr.trim()}`);
  const stats = JSON.parse(r.stdout.trim());
  return { file, ...stats, luma: Buffer.from(stats.luma, "base64") };
}
/** Share of the 64×36 samples whose luma moved by more than 12. */
const lumaChange = (a, b) =>
  a.luma.reduce((n, v, i) => n + (Math.abs(v - b.luma[i]) > 12 ? 1 : 0), 0) /
  a.luma.length;

// ---- app helpers --------------------------------------------------------------

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A command through the Wails bridge, as src/lib/native/wails.ts calls it. */
async function invoke(page, cmd, args = {}) {
  const r = await page.evaluate(
    async ([method, c, a]) => {
      const rt = await import("/wails/runtime.js");
      try {
        return { value: await rt.Call.ByName(method, c, a) };
      } catch (e) {
        return { error: JSON.stringify(e?.cause ?? e?.message ?? String(e)) };
      }
    },
    [INVOKE, cmd, args],
  );
  if ("error" in r) throw new Error(`${cmd}: ${r.error}`);
  return r.value;
}

/** Records the app's own `player://event` stream in the page (window.__smoke). */
const watchPlayer = (page) =>
  page.evaluate(async () => {
    if (window.__smoke) return;
    const rt = await import("/wails/runtime.js");
    const s = {
      types: {},
      times: [],
      paused: [],
      tracks: null,
      seeked: 0,
      log: [],
    };
    window.__smoke = s;
    rt.Events.On("player://event", (e) => {
      const d = e.data;
      s.types[d.type] = (s.types[d.type] ?? 0) + 1;
      if (d.type === "timePos" && d.seconds != null) {
        s.times.push([Date.now(), d.seconds]);
        if (s.times.length > 400) s.times.shift();
      }
      if (d.type === "paused") s.paused.push(d.paused);
      if (d.type === "tracks") s.tracks = d.tracks;
      if (d.type === "seeked") s.seeked++;
      if (["paused", "buffering", "seeked", "tracks"].includes(d.type)) {
        const v = d.type === "paused" ? d.paused : d.active;
        s.log.push([Date.now(), d.type, v ?? null]);
        if (s.log.length > 200) s.log.shift();
      }
    });
  });
const events = (page) => page.evaluate(() => window.__smoke);
const lastTime = async (page) => (await events(page)).times.at(-1)?.[1] ?? null;

async function poll(fn, accept, timeoutMs, stepMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (accept(last)) return last;
    await sleep(stepMs);
  }
  return last;
}

const diagnostics = (page) =>
  invoke(page, "player_diagnostics").catch(() => null);
const command = (page, c) => invoke(page, "player_command", { command: c });
const resumeOf = async (page, id) =>
  (await invoke(page, "emby_item_detail", { itemId: id })).resume;

/** In-viewport images under `scope` served from `prefix`. */
const images = (page, prefix, scope = "body") =>
  page.evaluate(
    ([p, sel]) => {
      const vw = innerWidth;
      const vh = innerHeight;
      const all = [...document.querySelectorAll(`${sel} img`)].filter((i) =>
        (i.currentSrc || i.src).startsWith(p),
      );
      const inView = all.filter((i) => {
        const r = i.getBoundingClientRect();
        return (
          r.width > 0 &&
          r.bottom > 0 &&
          r.right > 0 &&
          r.top < vh &&
          r.left < vw
        );
      });
      return {
        served: all.length,
        inView: inView.length,
        loaded: inView.filter((i) => i.complete && i.naturalWidth > 0).length,
        broken: all.filter((i) => i.complete && i.naturalWidth === 0).length,
      };
    },
    [prefix, scope],
  );
async function checkImages(page, name, prefix, scope, min) {
  const r = await poll(
    () => images(page, prefix, scope),
    (s) => s.loaded >= min && s.loaded === s.inView,
    20000,
    500,
  );
  check(
    name,
    r.loaded >= min && r.broken === 0,
    `${r.loaded}/${r.inView} visibles chargées, ${r.served} servies par ${new URL(prefix).pathname}, ${r.broken} cassées`,
  );
}

/** Starts the app; `ms` is the time to a page attached over CDP. */
async function startApp() {
  const t0 = Date.now();
  const app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
  app.attachMs = Date.now() - t0;
  app.foregroundIsApp = foregroundIsApp(app.proc.pid);
  return app;
}

/** Whether the foreground window belongs to the app (read only, no input). */
function foregroundIsApp(pid) {
  const ps = `
Add-Type -Name F -Namespace U -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow(); [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr h, out uint p);'
$p = 0; [U.F]::GetWindowThreadProcessId([U.F]::GetForegroundWindow(), [ref]$p) | Out-Null; $p`;
  const r = spawnSync("powershell", ["-NoProfile", "-Command", ps], {
    encoding: "utf8",
    windowsHide: true,
  });
  return Number(r.stdout.trim()) === pid;
}

/** Closes like ✕ (WM_CLOSE) and times the exit. */
async function closeTimed(app) {
  const t0 = Date.now();
  let code = null;
  try {
    code = await app.close(15000);
  } catch (e) {
    code = `error: ${e.message}`;
  }
  return { code, ms: Date.now() - t0 };
}

function vaultEntries() {
  return spawnSync("cmdkey", ["/list"], { encoding: "utf8" })
    .stdout.split(/\r?\n/)
    .filter((l) => /target=.*Viewer-debug/i.test(l)).length;
}

// ---- steps ------------------------------------------------------------------

async function firstRun(app) {
  const { page } = app;
  const screen = await startScreen(page);
  const empty = await page
    .getByText("Aucun serveur pour l'instant")
    .isVisible()
    .catch(() => false);
  check("first_run_screen", screen === "signedOut" && empty, screen);
  const info = await invoke(page, "app_info");
  fact("appInfo", info);
  check(
    "libmpv_found",
    info.libmpvFound === true,
    `surface ${info.surfaceMode}`,
  );
  await shot(page, "premier-lancement");
}

async function signInStep(page) {
  await signIn(page, account, account.password);
  await libraryNav(page).waitFor({ timeout: 30000 });
  const libraries = libraryNav(page).locator("ul.libraries button");
  await libraries.first().waitFor({ timeout: 20000 });
  check("signed_in", true, `${await libraries.count()} bibliothèques`);
  await homeHeading(page).waitFor({ timeout: 20000 });
  await checkImages(page, "home_artwork_loaded", EMBY_IMG, "main", 4);
  await shot(page, "accueil");
}

/** Films library, first film without a resume point; returns its id. */
async function openFilmsGrid(page) {
  const libraries = libraryNav(page).locator("ul.libraries button");
  const kinds = await libraries.evaluateAll((els) =>
    els.map((e) => e.querySelector(".kind")?.textContent?.trim() ?? ""),
  );
  const films = kinds.indexOf("Films");
  if (films < 0) throw new Error(`pas de bibliothèque Films (${kinds})`);
  await libraries.nth(films).click();
  await cards(page).first().waitFor({ timeout: 20000 });
}

async function openFilm(page, id) {
  await page.locator(`ul.grid button.card[data-item-id="${id}"]`).click();
  await detailTitle(page).waitFor({ timeout: 20000 });
  await page.waitForFunction(
    () =>
      document.querySelector(".detail")?.getAttribute("aria-busy") !== "true",
    null,
    { timeout: 20000 },
  );
}

async function startPlayback(page) {
  await page.locator(".detail button.play").first().click();
  state.played = true;
  await playerBadge(page).waitFor({ timeout: 30000 });
  return poll(
    () => diagnostics(page),
    (d) => (d?.timePos ?? 0) > 1,
    30000,
  );
}

async function leavePlayer(page) {
  for (let i = 0; i < 3 && (await playerBadge(page).count()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(600);
  }
  await playerBadge(page).waitFor({ state: "detached", timeout: 15000 });
}

/** Chooses another radio in the player's Audio or Sous-titres panel. */
async function switchTrack(page, panel, kind) {
  const before = (await invoke(page, "player_tracks")).filter(
    (t) => t.kind === kind,
  );
  const dialog = page.getByRole("dialog", { name: panel });
  // The controls fade after 3 s: a pointer move brings them back.
  await page.mouse.move(600, 400);
  await page.mouse.move(620, 420);
  await page
    .locator(".player")
    .getByRole("button", { name: panel, exact: true })
    .click();
  await dialog.waitFor({ timeout: 5000 });
  const radios = dialog.locator("input[type=radio]");
  const count = await radios.count();
  const checked = await radios.evaluateAll((els) =>
    els.findIndex((e) => e.checked),
  );
  if (count < 2) {
    await page.keyboard.press("Escape");
    return { switched: false, detail: `${count} choix seulement` };
  }
  const target = checked === count - 1 ? 0 : count - 1;
  await radios.nth(target).check();
  const after = await poll(
    () => invoke(page, "player_tracks"),
    (ts) =>
      JSON.stringify(
        ts.filter((t) => t.kind === kind).map((t) => t.selected),
      ) !== JSON.stringify(before.map((t) => t.selected)),
    8000,
  );
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "detached", timeout: 5000 });
  const sel = (ts) =>
    ts
      .filter((t) => t.kind === kind && t.selected)
      .map((t) => t.id)
      .join(",") || "aucune";
  return {
    switched: sel(after) !== sel(before),
    detail: `${count} choix, piste ${sel(before)} → ${sel(after)}`,
  };
}

async function playbackStep(app, filmId) {
  const { page } = app;
  await watchPlayer(page);
  const started = await startPlayback(page);
  check(
    "playback_started",
    (started?.timePos ?? 0) > 1,
    `${started?.timePos?.toFixed?.(1)} s`,
  );
  const badge = (await playerBadge(page).textContent()).trim();
  fact("decision", badge);
  // Away from a black opening, so the video capture has a picture.
  const seekAt = Date.now();
  await command(page, { type: "seekAbsolute", seconds: 300 });
  await poll(
    () => lastTime(page),
    (t) => (t ?? 0) > 301,
    20000,
  );
  // Events after the seek only: a film with a resume point starts past 300 s.
  const after300 = (s) =>
    s.times.filter(([at, t]) => at > seekAt && t >= 300 && t < 330);
  const ev = await poll(
    () => events(page),
    (s) => {
      const t = after300(s);
      return t.length > 1 && t.at(-1)[1] - t[0][1] > 3;
    },
    15000,
  );
  const first = after300(ev)[0];
  const last = ev.times.at(-1);
  check(
    "time_pos_advancing_events",
    first && last && last[1] - first[1] > 3 && last[0] > first[0],
    `${ev.types.timePos ?? 0} événements timePos, ${first?.[1].toFixed(1)} → ${last?.[1].toFixed(1)} s`,
  );
  const d = await diagnostics(page);
  fact("diagnostics", {
    hwdecCurrent: d?.hwdecCurrent,
    videoCodec: d?.videoCodec,
    videoSize: `${d?.videoWidth}×${d?.videoHeight}`,
    audioCodec: d?.audioCodec,
    audioOutput: d?.audioOutput,
    videoOutput: d?.videoOutput,
    gpuApi: d?.gpuApi,
    container: d?.container,
    mpvVersion: d?.mpvVersion,
  });
  check(
    "hwdec_reported",
    typeof d?.hwdecCurrent === "string" && d.hwdecCurrent.length > 0,
    `hwdec-current=${d?.hwdecCurrent} (${d?.videoCodec} ${d?.videoWidth}×${d?.videoHeight})`,
  );
  const tracks = await invoke(page, "player_tracks");
  const byKind = (k) => tracks.filter((t) => t.kind === k).length;
  fact(
    "tracks",
    tracks.map((t) => ({
      id: t.id,
      kind: t.kind,
      lang: t.lang,
      codec: t.codec,
      selected: t.selected,
      external: t.external,
    })),
  );
  check(
    "tracks_listed",
    byKind("video") >= 1 && byKind("audio") >= 1 && ev.tracks?.length > 0,
    `vidéo ${byKind("video")}, audio ${byKind("audio")}, sous-titres ${byKind("sub")}, événement tracks ${ev.tracks?.length ?? 0}`,
  );

  // Video under the overlay: the window paints it, the page capture does not.
  await page.mouse.move(640, 360);
  await page.mouse.move(650, 370);
  await page.waitForTimeout(400);
  const a = printWindow(app.proc.pid, "lecture-printwindow");
  await page.waitForTimeout(1500);
  const b = printWindow(app.proc.pid, "lecture-printwindow-2");
  await shot(page, "lecture-cdp");
  const moved = lumaChange(a, b);
  check(
    "video_under_controls_printwindow",
    a.printed && a.distinctColors > 150 && moved > 0.02,
    `${a.width}×${a.height}, ${a.distinctColors} couleurs sur 2304 points, luminance ${a.meanLuma}, ${(moved * 100).toFixed(1)} % des points changent en 1,5 s`,
  );

  // Track switches from the player panels.
  const audio = await switchTrack(page, "Audio", "audio");
  check(
    "audio_track_switch",
    audio.switched || /seulement/.test(audio.detail),
    audio.detail,
  );
  fact("audioSwitch", audio);
  const subs = await switchTrack(page, "Sous-titres", "sub");
  check(
    "subtitle_track_switch",
    subs.switched || /seulement/.test(subs.detail),
    subs.detail,
  );
  fact("subtitleSwitch", subs);

  // Keys through CDP into the page (Input.dispatchKeyEvent).
  // Focus off the panel button the Escape above returned to: Space would
  // otherwise also activate that button.
  await page.evaluate(() => document.activeElement?.blur());
  const pauseAt = Date.now();
  await page.keyboard.press("Space");
  const paused = await poll(
    () => diagnostics(page),
    (x) => x?.paused === true,
    5000,
  );
  const pausedAt = paused?.timePos;
  await page.waitForTimeout(1500);
  const still = await diagnostics(page);
  check(
    "space_pauses",
    paused?.paused === true && Math.abs((still?.timePos ?? 0) - pausedAt) < 0.2,
    `pause=${paused?.paused}, position ${pausedAt?.toFixed(1)} → ${still?.timePos?.toFixed(1)} s`,
  );
  const spinner = await page.locator(".player .buffering").count();
  await shot(page, "pause");
  // What the « Chargement… » indicator follows (mpv `paused-for-cache`).
  fact("pauseIndicators", {
    bufferingShownWhilePaused: spinner > 0,
    events: (await events(page)).log
      .filter(([t]) => t > pauseAt - 15000)
      .map(([t, type, v]) => [t - pauseAt, type, v]),
  });
  await page.keyboard.press("Space");
  const resumed = await poll(
    () => diagnostics(page),
    (x) => x?.paused === false,
    5000,
  );
  check("space_resumes", resumed?.paused === false);
  const beforeSeek = (await diagnostics(page))?.timePos ?? 0;
  const seekedBefore = (await events(page)).seeked;
  await page.keyboard.press("ArrowRight");
  const sought = await poll(
    () => diagnostics(page),
    (x) => (x?.timePos ?? 0) > beforeSeek + 5,
    8000,
  );
  // time-pos jumps at once; mpv's PLAYBACK_RESTART (seeked) follows the reload.
  const seekedAfter = (
    await poll(
      () => events(page),
      (s) => s.seeked > seekedBefore,
      8000,
    )
  ).seeked;
  check(
    "arrow_right_seeks",
    (sought?.timePos ?? 0) > beforeSeek + 5 && seekedAfter > seekedBefore,
    `${beforeSeek.toFixed(1)} → ${sought?.timePos?.toFixed(1)} s, événements seeked +${seekedAfter - seekedBefore}`,
  );

  fact("playerEventTypes", (await events(page)).types);

  // Stop: Escape leaves the player, the stop report gives Emby the position.
  await page.waitForTimeout(1500);
  const stopAt = (await diagnostics(page))?.timePos ?? 0;
  await leavePlayer(page);
  const engine = await diagnostics(page);
  check("stop_engine_gone", !engine || engine.timePos == null);
  const resume = await poll(
    () => resumeOf(page, filmId),
    (r) => r && Math.abs(r.positionSeconds - stopAt) < 20,
    15000,
    1000,
  );
  check(
    "stop_reported_resume_point",
    resume && Math.abs(resume.positionSeconds - stopAt) < 20,
    `arrêt à ${stopAt.toFixed(1)} s, reprise Emby ${resume?.positionSeconds?.toFixed(1) ?? "aucune"} s`,
  );
  await shot(page, "fiche-apres-arret");
}

async function demanderStep(page) {
  const nav = (name) =>
    libraryNav(page).getByRole("button", { name, exact: true });
  for (let i = 0; i < 3 && !(await libraryNav(page).isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
  }
  await nav("Demander").click();
  const setup = page.locator("#setup-ur-address");
  const segments = page.getByRole("group", { name: "Sections de Demander" });
  await Promise.race([
    setup.waitFor({ timeout: 20000 }),
    segments.waitFor({ timeout: 20000 }),
  ]);
  if (await setup.count()) {
    await setup.fill(account.ur);
    await page
      .getByRole("button", { name: "Se connecter", exact: true })
      .click();
  }
  await segments.waitFor({ timeout: 30000 });
  const totals = async () =>
    JSON.stringify(
      (await invoke(page, "ur_my_requests", { status: null, page: 1 })).totals,
    );
  const before = await totals();
  await page
    .locator(".row button.card, li button.card")
    .first()
    .waitFor({ timeout: 30000 });
  check(
    "demander_loads",
    true,
    `${await page.locator("button.card").count()} cartes`,
  );
  await checkImages(page, "demander_artwork_loaded", UR_IMG, "main", 3);
  await shot(page, "demander");
  check("demander_read_only", (await totals()) === before, "totaux inchangés");
}

async function closeDuringPlayback(app, filmId) {
  const { page } = app;
  await openFilmsGrid(page);
  await openFilm(page, filmId);
  const started = await startPlayback(page);
  // Away from the resume point already on the server, so only the report sent
  // at close can match.
  const target = (started?.timePos ?? 0) + 60;
  await command(page, { type: "seekAbsolute", seconds: target });
  await poll(
    () => diagnostics(page),
    (d) => (d?.timePos ?? 0) > target + 2,
    20000,
  );
  const at = (await diagnostics(page))?.timePos ?? 0;
  fact("closeAt", at);
  const exit = await closeTimed(app);
  fact("closeDuringPlayback", exit);
  check(
    "close_during_playback_exit",
    exit.code === 0 && exit.ms < EXIT_LIMIT_MS,
    `code ${exit.code}, sortie en ${exit.ms} ms (limite ${EXIT_LIMIT_MS} ms)`,
  );
  return at;
}

async function afterRestart(page, filmId, closedAt) {
  check("session_restored", (await startScreen(page)) === "signedIn");
  await homeHeading(page).waitFor({ timeout: 20000 });
  const resume = await poll(
    () => resumeOf(page, filmId),
    (r) => r && Math.abs(r.positionSeconds - closedAt) < 3,
    10000,
    1000,
  );
  check(
    "close_reported_resume_point",
    resume && Math.abs(resume.positionSeconds - closedAt) < 3,
    `fermeture à ${closedAt.toFixed(1)} s, reprise Emby ${resume?.positionSeconds?.toFixed(1) ?? "aucune"} s`,
  );
  await clearResume(page, filmId);
}

/** Leaves the demo account as found: rewind to 0 and stop (gate3-player). */
async function clearResume(page, filmId) {
  await openFilmsGrid(page);
  await openFilm(page, filmId);
  await startPlayback(page);
  await command(page, { type: "seekAbsolute", seconds: 0 });
  await poll(
    () => diagnostics(page),
    (d) => (d?.timePos ?? 99) < 3 && (d?.timePos ?? 0) > 0,
    15000,
  );
  await leavePlayer(page);
  const cleared = await poll(
    () => resumeOf(page, filmId),
    (r) => r === null,
    15000,
    1000,
  );
  check("resume_point_cleared", cleared === null);
  state.resumeCleared = cleared === null;
}

/** After a failure: back to the grid, resume point cleared, both sign-outs. */
async function cleanup(page) {
  if (await playerBadge(page).count()) await leavePlayer(page);
  for (let i = 0; i < 4 && !(await libraryNav(page).isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
  }
  if (state.played && !state.resumeCleared)
    await clearResume(page, state.filmId);
  await invoke(page, "ur_sign_out").catch(() => {});
  await signOut(page);
  state.signedOut = true;
}

function logFacts() {
  const file = join(out, "logs", "viewer.log");
  if (!existsSync(file)) return check("app_log_written", false);
  const log = readFileSync(file, "utf8");
  const errors = log.split(/\r?\n/).filter((l) => / ERROR /.test(l));
  fact("logErrors", errors.length);
  const leaked = secrets.filter((v) => log.includes(v)).length;
  check(
    "log_without_account_values",
    leaked === 0,
    `${errors.length} lignes ERROR, ${leaked} valeur(s) du compte`,
  );
  if (!OFFLINE)
    check(
      "log_stop_report_confirmed_at_close",
      !log.includes("playback stop report not confirmed before exit"),
    );
}

// ---- run --------------------------------------------------------------------

let app = null;
const vaultBefore = vaultEntries();
/** What the cleanup must undo if a step fails. */
const state = {
  signedOut: false,
  filmId: null,
  played: false,
  resumeCleared: false,
};
try {
  if (argv.includes("--build")) buildDebug();
  if (!existsSync(exe)) throw new Error(`build absent : ${exe} (--build)`);
  if (vaultBefore > 0 && !OFFLINE)
    throw new Error("le coffre a déjà des entrées Viewer-debug");

  app = await startApp();
  check("cdp_attached", true, `${app.page.url()} en ${app.attachMs} ms`);
  fact("foregroundTakenAtLaunch", app.foregroundIsApp);
  fact(
    "systemReport",
    await invoke(app.page, "system_report").catch((e) => e.message),
  );
  await firstRun(app);

  if (OFFLINE) {
    printWindow(app.proc.pid, "premier-lancement-printwindow");
    const exit = await closeTimed(app);
    app = null;
    check(
      "exit_idle",
      exit.code === 0 && exit.ms < EXIT_LIMIT_MS,
      `code ${exit.code}, ${exit.ms} ms`,
    );
  } else {
    const { page } = app;
    await signInStep(page);
    await openFilmsGrid(page);
    await checkImages(page, "grid_artwork_loaded", EMBY_IMG, "ul.grid", 4);
    let filmId = filmArg >= 0 ? argv[filmArg + 1] : null;
    for (
      let n = 0;
      !filmId && n < Math.min(12, await cards(page).count());
      n++
    ) {
      const id = await cards(page).nth(n).getAttribute("data-item-id");
      if ((await resumeOf(page, id)) === null) {
        filmId = id;
        break;
      }
    }
    if (!filmId) throw new Error("aucun film sans point de reprise");
    state.filmId = filmId;
    await openFilm(page, filmId);
    check("film_detail", true, `film ${filmId}`);
    await checkImages(page, "detail_artwork_loaded", EMBY_IMG, ".detail", 1);
    await shot(page, "fiche-film");

    await playbackStep(app, filmId);
    await demanderStep(page);
    const closedAt = await closeDuringPlayback(app, filmId);
    app = null;

    app = await startApp();
    await afterRestart(app.page, filmId, closedAt);
    await invoke(app.page, "ur_sign_out").catch((e) =>
      check("ur_sign_out", false, e.message),
    );
    await signOut(app.page);
    state.signedOut = true;
    check("signed_out", await signInHeading(app.page).isVisible());
    await shot(app.page, "deconnecte");
    const exit = await closeTimed(app);
    app = null;
    check("exit_after_sign_out", exit.code === 0, `${exit.ms} ms`);
  }
} catch (e) {
  check(
    "scenario_error",
    false,
    e instanceof Error ? e.message.split("\n")[0] : e,
  );
  if (app) await shot(app.page, "erreur").catch(() => {});
} finally {
  // A failed step must not leave a session in the vault or a resume point on
  // the demo account: clean up in the running app, else in a restarted one.
  if (!OFFLINE && !state.signedOut && vaultEntries() > vaultBefore) {
    try {
      let signedIn = true;
      if (!app || app.proc.exitCode !== null) {
        app = await startApp();
        signedIn = (await startScreen(app.page)) === "signedIn";
      }
      if (signedIn) {
        await cleanup(app.page);
        check("cleanup_after_failure", true);
      } else check("cleanup_after_failure", false, "session non restaurée");
    } catch (e) {
      check("cleanup_after_failure", false, String(e?.message).split("\n")[0]);
    }
  }
  if (app) await app.close().catch(() => {});
  logFacts();
  const left = vaultEntries();
  check(
    "no_vault_entry_left",
    left === 0,
    `${left} entrée(s) Viewer-debug`,
  );
  // WebView2 may hold its profile a moment after the app exits.
  for (let i = 0; i < 10 && existsSync(profile); i++) {
    try {
      rmSync(profile, { recursive: true, force: true });
    } catch {
      await sleep(1000);
    }
  }
  check("profile_removed", !existsSync(profile));
  report.finishedAt = new Date().toISOString();
  report.passed = checks.every((c) => c.passed);
  writeFileSync(
    join(out, "report.json"),
    scrub(JSON.stringify(report, null, 2)),
  );
  console.log(`${report.passed ? "ALL PASSED" : "FAILURES"} — ${out}`);
  process.exitCode = report.passed ? 0 : 1;
}
