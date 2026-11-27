// Shared pieces of the Stremio scenarios (stremio-settings, stremio-sources,
// stremio-watch-now): accounts read once and redacted, a report whose every
// line is scrubbed, the run's folders, the Windows vault, PrintWindow captures
// with the account values masked, the Réglages › Stremio panel, the
// « Autres sources » dialog, the player of a Stremio stream and the checks of
// the app log at the end.
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { root } from "./app.mjs";
import {
  debugEnv,
  invoke,
  libraryNav,
  signIn,
  startScreen,
} from "./scenario.mjs";

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** `fn()` every `stepMs` until `accept` takes it, or its last value at the end. */
export async function poll(fn, accept, timeoutMs, stepMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (accept(last)) return last;
    await sleep(stepMs);
  }
  return last;
}

/**
 * The run's folders: `out` (--out, else <tmp>/viewer-<name>-<timestamp>) for
 * the captures and report.json, and `work`, a temporary folder for the app's
 * APPDATA, LOCALAPPDATA and log (`appEnv`), deleted at the end.
 */
export function runFolders(name, argv) {
  const at = argv.indexOf("--out");
  const stamp = new Date()
    .toISOString()
    .replace(/[-:]/g, "")
    .replace("T", "-")
    .slice(0, 15);
  const out = at >= 0 ? argv[at + 1] : join(tmpdir(), `viewer-${name}-${stamp}`);
  mkdirSync(out, { recursive: true });
  const work = mkdtempSync(join(tmpdir(), `viewer-e2e-${name}-`));
  return {
    out,
    work,
    logFile: join(work, "logs", "viewer.log"),
    appEnv: {
      APPDATA: join(work, "Roaming"),
      LOCALAPPDATA: join(work, "Local"),
      VIEWER_E2E_LOG_DIR: join(work, "logs"),
    },
  };
}

/** Vault target of the Stremio authKey in the debug build. */
export const STREMIO_VAULT_TARGET = "stremio.Viewer-debug";

const optionalEnv = (key) => {
  try {
    return debugEnv(key);
  } catch {
    return "";
  }
};
const hosts = (u) => {
  try {
    return [new URL(u).host, new URL(u).hostname];
  } catch {
    return [];
  }
};

/**
 * The Emby account of debug.env and the Stremio credentials of the files named
 * by ST_EMAIL_FILE / ST_PASS_FILE (null without them), then the variables are
 * removed so the app process never gets them. `secrets` lists every value that
 * must never be printed, written or captured.
 */
export function readAccounts() {
  const account = {
    url: `http://${debugEnv("EMBY_IP")}`,
    user: debugEnv("LOGIN"),
    password: debugEnv("PASSWORD"),
  };
  const urUrl = optionalEnv("VIEWER_UR_URL");
  const file = (name) =>
    readFileSync(process.env[name], "utf8").replace(/\r?\n$/, "");
  const stremio =
    process.env.ST_EMAIL_FILE && process.env.ST_PASS_FILE
      ? { email: file("ST_EMAIL_FILE").trim(), password: file("ST_PASS_FILE") }
      : null;
  for (const key of [
    "EMBY_IP",
    "LOGIN",
    "PASSWORD",
    "VIEWER_UR_URL",
    "ST_EMAIL_FILE",
    "ST_PASS_FILE",
  ])
    delete process.env[key];
  const secrets = [
    account.password,
    account.url,
    ...hosts(account.url),
    account.user,
    urUrl,
    ...hosts(urUrl),
    stremio?.email,
    stremio?.password,
  ].filter((s) => s && s.length >= 3);
  return { account, urUrl, stremio, secrets };
}

/**
 * `scrub(value)`: `secrets` (the live array: values pushed later count) and
 * any address replaced, for anything printed or written.
 */
export function scrubber(secrets) {
  const scrub = (value) =>
    [...secrets]
      .sort((a, b) => b.length - a.length)
      .reduce((t, s) => t.split(s).join("«masqué»"), String(value))
      .replace(/https?:\/\/\S+/gi, "«adresse»");
  const firstLine = (e) =>
    scrub(e instanceof Error ? e.message : String(e)).split("\n")[0];
  return { scrub, firstLine };
}

/** Checks printed and kept scrubbed; `finish(out)` writes report.json. */
export function scrubbedReport(scrub, fields = {}) {
  const checks = [];
  const report = { startedAt: new Date().toISOString(), ...fields, checks };
  return {
    report,
    check(name, passed, detail = "") {
      const d = scrub(detail);
      checks.push({ name, passed: Boolean(passed), detail: d });
      console.log(`${passed ? "PASS" : "FAIL"} ${name}${d ? ` — ${d}` : ""}`);
    },
    skip(name, why) {
      checks.push({ name, passed: true, skipped: true, detail: why });
      console.log(`SKIP ${name} — ${why}`);
    },
    finish(out) {
      report.finishedAt = new Date().toISOString();
      report.passed = checks.length > 0 && checks.every((c) => c.passed);
      writeFileSync(
        join(out, "report.json"),
        scrub(JSON.stringify(report, null, 2)),
      );
      console.log(`${report.passed ? "ALL PASSED" : "FAILURES"} — ${out}`);
      process.exitCode = report.passed ? 0 : 1;
    },
  };
}

// ---- vault ------------------------------------------------------------------

/** `target=` lines of `cmdkey /list` matching `re` (never printed). */
export const vaultTargets = (re) =>
  spawnSync("cmdkey", ["/list"], { encoding: "utf8", windowsHide: true })
    .stdout.split(/\r?\n/)
    .filter((l) => /target=/i.test(l) && re.test(l)).length;

/** The authKey stored by the app (CredReadW, UTF-16 blob), or null. Memory only. */
export function readAuthKey() {
  const ps = `
Add-Type -Name C -Namespace U -MemberDefinition '[DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] public static extern bool CredReadW(string target, int type, int flags, out System.IntPtr cred); [DllImport("advapi32.dll")] public static extern void CredFree(System.IntPtr p);'
$p = [System.IntPtr]::Zero
if (-not [U.C]::CredReadW('${STREMIO_VAULT_TARGET}', 1, 0, [ref]$p)) { exit 3 }
$size = [System.Runtime.InteropServices.Marshal]::ReadInt32($p, 32)
$blob = [System.Runtime.InteropServices.Marshal]::ReadIntPtr($p, 40)
$bytes = New-Object byte[] $size
[System.Runtime.InteropServices.Marshal]::Copy($blob, $bytes, 0, $size)
[U.C]::CredFree($p)
[Convert]::ToBase64String($bytes)`;
  const r = spawnSync("powershell", ["-NoProfile", "-Command", ps], {
    encoding: "utf8",
    windowsHide: true,
  });
  if (r.status !== 0) return null;
  const key = Buffer.from(r.stdout.trim(), "base64").toString("utf16le");
  return key.length >= 8 ? key : null;
}

// ---- captures ---------------------------------------------------------------

/**
 * PrintWindow of the app's own window (video included) as `<out>/<name>.png`,
 * `secrets` masked in the page while it is captured.
 */
export async function capture(app, out, name, secrets) {
  const file = join(out, `${name}.png`);
  await app.page.evaluate((hide) => {
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
    // Two frames, so the masked page is what the window paints.
    await app.page.evaluate(
      () =>
        new Promise((r) =>
          requestAnimationFrame(() => requestAnimationFrame(r)),
        ),
    );
    await app.page.waitForTimeout(300);
    const r = spawnSync(
      "powershell",
      [
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        join(root, "scripts", "e2e", "print-window.ps1"),
        "-ProcessId",
        String(app.proc.pid),
        "-Path",
        file,
      ],
      { encoding: "utf8", windowsHide: true },
    );
    if (r.status !== 0) throw new Error(`PrintWindow: ${r.stderr.trim()}`);
    const { printed, width, height, distinctColors } = JSON.parse(
      r.stdout.trim(),
    );
    return { file, printed, width, height, distinctColors };
  } finally {
    await app.page.evaluate(() => {
      for (const undo of window.__masked ?? []) undo();
      window.__masked = [];
    });
  }
}

/** Deletes the run's temporary folder; WebView2 may hold it a moment. */
export async function removeWork(work) {
  for (let i = 0; i < 10 && existsSync(work); i++) {
    try {
      rmSync(work, { recursive: true, force: true });
    } catch {
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  return !existsSync(work);
}

// ---- Réglages › Stremio -----------------------------------------------------

export const settingsPanel = (page) =>
  page.locator("section[aria-labelledby=settings-stremio]");
export const settingsState = async (page) =>
  (await settingsPanel(page).locator(".state").textContent())
    ?.replace(/\s+/g, " ")
    .trim();
export const emailField = (page) =>
  settingsPanel(page).locator("#stremio-email");
export const passwordField = (page) =>
  settingsPanel(page).locator("#stremio-password");
export const settingsButton = (page, name) =>
  settingsPanel(page).getByRole("button", { name, exact: true });

/** Réglages from the navigation, its Stremio panel shown. */
export async function openSettings(page) {
  await libraryNav(page)
    .getByRole("button", { name: "Réglages", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Réglages", level: 1 })
    .waitFor({ timeout: 15000 });
  await settingsPanel(page).waitFor({ timeout: 15000 });
}

/** Waits for the panel to settle on `label` (« Connecté », « Déconnecté »…). */
export async function waitForSettingsState(page, label, timeout = 30000) {
  await page.waitForFunction(
    (want) => {
      const p = document.querySelector(
        "section[aria-labelledby=settings-stremio]",
      );
      const s = p?.querySelector(".state")?.textContent?.trim();
      return p?.getAttribute("aria-busy") === "false" && s === want;
    },
    label,
    { timeout },
  );
}

/** Signs in from the panel and waits for « Connecté ». */
export async function signInFromSettings(page, { email, password }) {
  await emailField(page).fill(email);
  await passwordField(page).fill(password);
  await settingsButton(page, "Se connecter").click();
  await waitForSettingsState(page, "Connecté", 45000);
}

// ---- start and end --------------------------------------------------------------

/** libmpv found (else an error), and the page keeps the focus under CDP. */
export async function readyApp(page, check) {
  const info = await invoke(page, "app_info");
  check("libmpv_found", info.libmpvFound === true);
  if (!info.libmpvFound)
    throw new Error(
      "libmpv absent : vendor/mpv/libmpv-2.dll (fetch-libmpv.ps1)",
    );
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });
}

/**
 * Emby sign-in with `account`, then Stremio from Réglages; `state.emby` and
 * `state.stremio` are set as each starts (the end undoes them), `onStep`
 * names the step. Returns the authKey read from the vault, added to `secrets`.
 */
export async function signInEmbyAndStremio(
  page,
  { account, stremio, check, state, secrets, onStep },
) {
  check("first_run_signed_out", (await startScreen(page)) === "signedOut");
  onStep("connexion Emby");
  await signIn(page, account, account.password);
  state.emby = true;
  await libraryNav(page).waitFor({ timeout: 30000 });
  onStep("connexion Stremio");
  await openSettings(page);
  await waitForSettingsState(page, "Déconnecté", 20000);
  state.stremio = true;
  await signInFromSettings(page, stremio);
  check("stremio_signed_in", true);
  const authKey = readAuthKey();
  if (authKey) secrets.push(authKey);
  check("auth_key_in_vault", authKey !== null);
  return authKey;
}

/** The player closed, Stremio signed out, its vault entry gone. */
export async function signOutStremio(page, state, check) {
  if (await decision(page).count()) await leavePlayer(page);
  if (state.stremio || vaultTargets(/stremio/i) > 0) {
    await invoke(page, "stremio_sign_out");
    state.stremio = false;
  }
  const left = vaultTargets(/stremio/i) > 0;
  console.log(`cmdkey stremio target present: ${left}`);
  check("stremio_signed_out_vault_empty", !left, String(left));
}

/** After a failed end: the key must not outlive the run. */
export async function lastStremioSignOut(app) {
  if (vaultTargets(/stremio/i) > 0 && app && app.proc.exitCode === null)
    await app.page
      .evaluate(() => window.__viewer.invoke("stremio_sign_out"))
      .catch(() => {});
}

// ---- navigation ---------------------------------------------------------------

/** Escape out of detail pages and the player until the navigation shows. */
export async function toNav(page) {
  for (let i = 0; i < 5 && !(await libraryNav(page).isVisible()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
  }
  await libraryNav(page).waitFor({ timeout: 10000 });
}

/** Text of the focused element and whether it is in « Autres sources ». */
export const focusInfo = (page) =>
  page.evaluate(() => {
    const a = document.activeElement;
    return {
      text: a?.textContent?.trim() ?? "",
      tag: a?.tagName ?? "",
      inDialog: !!a?.closest("dialog[aria-labelledby=sources-title]"),
    };
  });

// ---- « Autres sources » ---------------------------------------------------------

/** The panel's message after a link that stopped answering. */
export const SOURCE_FAILED =
  "Ce lien ne répond plus, choisis une autre source.";

export const sourcesDialog = (page) =>
  page.locator("dialog[aria-labelledby=sources-title]");

/** Every addon section with its state, read from the dialog. */
export const panelState = (page) =>
  page.evaluate(() => {
    const d = document.querySelector("dialog[aria-labelledby=sources-title]");
    if (!d?.open) return { open: false, addons: [] };
    const addons = [...d.querySelectorAll("section.addon")].map((s) => {
      const name = s.querySelector("h3")?.textContent?.trim() ?? "";
      const streams = s.querySelectorAll("button.stream").length;
      const note = s.querySelector("p.muted")?.textContent?.trim() ?? "";
      const state =
        streams > 0
          ? "ok"
          : note === "Recherche…"
            ? "loading"
            : note === "Aucun flux."
              ? "empty"
              : "error";
      return { name, state, streams, note: state === "error" ? note : "" };
    });
    return {
      open: true,
      busy: d.getAttribute("aria-busy") === "true",
      // « Recherche dans N sources… » while addons are pending: only the
      // addons with streams have a section.
      searching:
        d.querySelector(".st-head p.muted[role=status]")?.textContent?.trim() ??
        null,
      error: d.querySelector(".list .state")?.textContent?.trim() ?? null,
      notice: d.querySelector(".notice")?.textContent?.trim() ?? null,
      unsupported: d.querySelector(".foot .muted")?.textContent?.trim() ?? null,
      addons,
    };
  });

/**
 * Samples the panel until every addon answered (list loaded, no « Recherche
 * dans N sources… » left); `progressive` when a sample showed sections while
 * addons were still pending.
 */
export async function waitSettled(page, timeoutMs = 45000) {
  const t0 = Date.now();
  const settledAt = {};
  let progressive = false;
  let last = null;
  while (Date.now() - t0 < timeoutMs) {
    last = await panelState(page);
    if (last.error) break;
    const states = last.addons.map((a) => a.state);
    for (const a of last.addons)
      if (a.state !== "loading" && !(a.name in settledAt))
        settledAt[a.name] = Date.now() - t0;
    if (
      (last.searching || states.includes("loading")) &&
      states.some((s) => s !== "loading")
    )
      progressive = true;
    if (
      last.open &&
      !last.busy &&
      !last.searching &&
      !states.includes("loading")
    )
      break;
    await sleep(100);
  }
  const times = Object.values(settledAt);
  return {
    ...last,
    progressive,
    firstMs: times.length ? Math.min(...times) : null,
    lastMs: times.length ? Math.max(...times) : null,
  };
}

/** « name: streams » (or its state) of each addon of a settled panel. */
export const addonsSummary = (settled) =>
  [
    ...settled.addons.map(
      (a) => `${a.name}: ${a.state === "ok" ? a.streams : a.state}`,
    ),
    ...(settled.searching ? [settled.searching] : []),
  ].join(" ; ");

/** The « Lecture » choice of the panel (local resume), copied from the app. */
export const RESUME_LEGEND = "Lecture";
export const RESUME_PREFIX = "Reprendre à ";
export const RESTART_LABEL = "Depuis le début";

/**
 * The radio group of the open panel: its legend and each choice's label and
 * state, or null without a fieldset.
 */
export const resumeChoice = (page) =>
  sourcesDialog(page).evaluate((d) => {
    const f = d.querySelector("fieldset");
    if (!f) return null;
    return {
      legend: f.querySelector("legend")?.textContent?.trim() ?? "",
      choices: [...f.querySelectorAll("label")].map((l) => ({
        text: l.textContent?.replace(/\s+/g, " ").trim() ?? "",
        checked: l.querySelector("input[type=radio]")?.checked === true,
      })),
    };
  });

/** Seconds of the `m:ss` or `h:mm:ss` time ending `text`, or null. */
export function clockSeconds(text) {
  const m = /(\d+(?::\d{2}){1,2})\s*$/.exec(text);
  return m ? m[1].split(":").reduce((t, p) => t * 60 + Number(p), 0) : null;
}

/**
 * Tab (at most `max` times) until a radio of the panel's fieldset has the
 * focus; its label and state, or `reached: false`.
 */
export async function tabToRadio(page, max = 8) {
  for (let i = 0; i < max; i++) {
    await page.keyboard.press("Tab");
    const at = await page.evaluate(() => {
      const a = document.activeElement;
      const radio = a instanceof HTMLInputElement && a.type === "radio";
      return {
        reached:
          radio &&
          !!a.closest("dialog[aria-labelledby=sources-title] fieldset"),
        text:
          a?.closest("label")?.textContent?.replace(/\s+/g, " ").trim() ?? "",
        checked: radio && a.checked,
      };
    });
    if (at.reached) return at;
  }
  return { reached: false, text: "", checked: false };
}

export async function closePanel(page) {
  await page.keyboard.press("Escape");
  await sourcesDialog(page).waitFor({ state: "detached", timeout: 10000 });
}

/** The dialog's markup: no address, manifest or debrid host, booleans only. */
export const dialogLeaks = (page) =>
  sourcesDialog(page).evaluate((d) => {
    const html = d.outerHTML;
    return {
      address: /https?:|:\/\//i.test(html),
      manifest: /manifest\.json/i.test(html),
      debridHost:
        /[a-z0-9-]*debrid[a-z0-9-]*\.(com|net|org|io|app|fr|link|it|me|cc|xyz|tv)\b/i.test(
          html,
        ),
    };
  });

// ---- the player -------------------------------------------------------------------

export const decision = (page) => page.locator(".player .decision");

/** Records the app's own `player://event` stream in the page (window.__st). */
export async function watchPlayer(page) {
  await page.evaluate(async () => {
    if (!window.__st) {
      const rt = await import("/wails/runtime.js");
      rt.Events.On("player://event", (e) => {
        const d = e.data;
        const s = window.__st;
        if (d.type === "fileLoaded") s.loaded.push(Date.now());
        if (d.type === "timePos" && d.seconds != null)
          s.times.push([Date.now(), d.seconds]);
        if (d.type === "duration") s.duration = d.seconds;
        if (d.type === "endFile") s.ends.push([d.reason, d.error ?? null]);
        if (s.times.length > 400) s.times.shift();
      });
    }
    window.__st = { loaded: [], times: [], ends: [], duration: null };
  });
}
export const events = (page) => page.evaluate(() => window.__st);
export const playerCommand = (page, c) =>
  invoke(page, "player_command", { command: c });

/** Escape until the player is gone. */
export async function leavePlayer(page) {
  for (let i = 0; i < 3 && (await decision(page).count()); i++) {
    await page.keyboard.press("Escape");
    await page.waitForTimeout(600);
  }
  await decision(page).waitFor({ state: "detached", timeout: 15000 });
}

/**
 * Plays the first stream of `addon` from the open panel. Success: `fileLoaded`
 * within 30 s and 3 s of position. A failed link brings the panel back with
 * its message; a stalled one is closed here.
 */
export async function tryAddon(page, addon) {
  await watchPlayer(page);
  const t0 = Date.now();
  await sourcesDialog(page)
    .locator("section.addon", {
      has: page.locator("h3", { hasText: addon }),
    })
    .locator("button.stream")
    .first()
    .click();
  const outcome = await poll(
    async () => {
      const ev = await events(page);
      const panel = await panelState(page);
      return { ev, panel, player: (await decision(page).count()) > 0 };
    },
    ({ ev, panel, player }) =>
      ev.loaded.length > 0 ||
      ev.ends.some(([r]) => r === "error") ||
      (panel.open && panel.notice) ||
      (!player && !panel.open && Date.now() - t0 > 5000),
    30000,
    200,
  );
  const loadedMs = outcome.ev.loaded[0] ? outcome.ev.loaded[0] - t0 : null;
  if (loadedMs === null) {
    if (await decision(page).count()) await leavePlayer(page);
    const reason = outcome.panel.notice
      ? outcome.panel.notice === SOURCE_FAILED
        ? "lien qui ne répond plus"
        : `refusé : ${outcome.panel.notice}`
      : outcome.ev.ends.length
        ? `fin ${outcome.ev.ends.map(([r]) => r).join(",")}`
        : "pas de fileLoaded en 30 s";
    return { ok: false, reason };
  }
  // The position moves 3 s past the first one reported.
  const moved = await poll(
    () => events(page),
    (s) => s.times.length > 1 && s.times.at(-1)[1] - s.times[0][1] >= 3,
    30000,
  );
  const first = moved.times[0]?.[1];
  const last = moved.times.at(-1)?.[1];
  if (!(last - first >= 3)) {
    await leavePlayer(page);
    return {
      ok: false,
      loadedMs,
      reason: `position ${first?.toFixed(1)} → ${last?.toFixed(1)} s`,
    };
  }
  return { ok: true, loadedMs, from: first, to: last };
}

/**
 * The first stream of the first addon with streams, else of up to `others`
 * other addons; `open()` shows the panel again when a failed link closed it.
 * `played` is null when none played.
 */
export async function playFirstWorking(page, open, others = 3) {
  if (!(await sourcesDialog(page).isVisible())) await open();
  let panel = await waitSettled(page);
  const candidates = panel.addons
    .filter((a) => a.state === "ok")
    .map((a) => a.name)
    .slice(0, 1 + others);
  const attempts = [];
  for (const addon of candidates) {
    if (!(await sourcesDialog(page).isVisible())) await open();
    panel = await waitSettled(page);
    if (!panel.addons.some((a) => a.name === addon && a.state === "ok")) {
      attempts.push({ addon, ok: false, reason: "plus de flux" });
      continue;
    }
    const r = await tryAddon(page, addon);
    attempts.push({ addon, ...r });
    if (r.ok) return { attempts, played: { addon, ...r } };
  }
  return { attempts, played: null };
}

/** One line per attempt of `playFirstWorking`. */
export const attemptsText = (attempts) =>
  attempts
    .map(
      (a) =>
        `${a.addon}: ${a.ok ? `lu (fileLoaded en ${a.loadedMs} ms, ${a.from.toFixed(1)} → ${a.to.toFixed(1)} s)` : `échec (${a.reason})`}`,
    )
    .join(" ; ");

/**
 * Past Emby's 10 s progress interval, far into the stream (a seek to 40 % of
 * a stream longer than 2 min): any report would leave a trace on Emby.
 */
export async function holdPastProgressInterval(page) {
  const duration = (await events(page)).duration;
  if (duration > 120) {
    const target = Math.round(duration * 0.4);
    await playerCommand(page, { type: "seekAbsolute", seconds: target });
    await poll(
      () => events(page),
      (s) => (s.times.at(-1)?.[1] ?? 0) > target + 1,
      30000,
    );
  }
  const holdFrom = (await events(page)).times.at(-1)?.[1] ?? 0;
  await poll(
    () => events(page),
    (s) => (s.times.at(-1)?.[1] ?? 0) > holdFrom + 12,
    40000,
  );
  const stopAt = (await events(page)).times.at(-1)?.[1] ?? 0;
  return { duration, holdFrom, stopAt };
}

/**
 * Seeks to `target` (absolute) and holds `holdSeconds` of playback past the
 * first position reported after the seek (one before it is the old one). Returns
 * that position and the last one, or null when the seek does not land in 30 s.
 */
export async function seekAndHold(page, target, holdSeconds = 3) {
  const sent = Date.now();
  await playerCommand(page, { type: "seekAbsolute", seconds: target });
  const landed = (s) =>
    s.times.find(([at, v]) => at > sent && v >= target && v < target + 60);
  const seen = await poll(
    () => events(page),
    (s) => landed(s),
    30000,
  );
  const reached = landed(seen)?.[1];
  if (reached === undefined) return null;
  const held = await poll(
    () => events(page),
    (s) => (s.times.at(-1)?.[1] ?? 0) >= reached + holdSeconds,
    20000,
  );
  return { reached, last: held.times.at(-1)?.[1] ?? reached };
}

/**
 * The entries of the Stremio resume file found under `dir` (the app's APPDATA
 * of the run), as position and duration only; null without a readable file.
 */
export function readResumeFile(dir) {
  try {
    const file = readdirSync(dir, { recursive: true }).find(
      (f) => basename(f) === "stremio-resume.json",
    );
    if (!file) return null;
    const data = JSON.parse(readFileSync(join(dir, file), "utf8"));
    return Object.values(data).map((e) => ({
      positionSeconds: e.positionSeconds,
      durationSeconds: e.durationSeconds,
    }));
  } catch {
    return null;
  }
}

// ---- Emby's trace ---------------------------------------------------------------------

/** Emby's view of an item: resume point, played, in « Reprendre ». */
export async function embyTrace(page, id) {
  const d = await invoke(page, "emby_item_detail", { itemId: id });
  const cw = await invoke(page, "emby_continue_watching");
  return {
    resume: d.resume?.positionSeconds ?? null,
    played: d.played,
    continueWatching: cw.some((w) => w.id === id),
  };
}
export const sameTrace = (a, b) =>
  a.resume === b.resume &&
  a.played === b.played &&
  a.continueWatching === b.continueWatching;
export const traceText = (t) =>
  `reprise ${t.resume === null ? "aucune" : `${t.resume.toFixed(0)} s`}, vu ${t.played}, dans Reprendre ${t.continueWatching}`;

// ---- end of the run -----------------------------------------------------------------

/**
 * The app log (`logFile`) holds no manifest or stream address, no address
 * outside `allowedUrls`, no authKey and no account value; then no `stremio`
 * nor Viewer-debug entry is left in the vault. Returns the log, or null.
 */
export function checkLogAndVault(
  check,
  { logFile, allowedUrls, authKey, secrets },
) {
  let log = null;
  if (!existsSync(logFile)) check("log_written", false, "journal absent");
  else {
    log = readFileSync(logFile, "utf8");
    const allowed = new Set(
      allowedUrls
        .filter(Boolean)
        .flatMap((u) => {
          try {
            return [new URL(u).host];
          } catch {
            return [];
          }
        })
        .concat(["wails.localhost", "localhost", "127.0.0.1"]),
    );
    const foreign = (log.match(/https?:\/\/[^\s"'\\<>]+/gi) ?? []).filter(
      (u) => {
        try {
          return !allowed.has(new URL(u).host);
        } catch {
          return true;
        }
      },
    ).length;
    check(
      "log_without_stream_addresses_or_key",
      !/manifest\.json/i.test(log) &&
        foreign === 0 &&
        (authKey === null || !log.includes(authKey)),
      `manifest.json ${/manifest\.json/i.test(log)}, ${foreign} adresse(s) hors Emby, authKey ${authKey ? log.includes(authKey) : "non lu"}`,
    );
    const leaked = secrets.filter((s) => log.includes(s)).length;
    check("log_without_account_values", leaked === 0, `${leaked} valeur(s)`);
  }
  check("no_stremio_vault_entry", vaultTargets(/stremio/i) === 0);
  check(
    "no_debug_vault_entry",
    vaultTargets(/Viewer-debug/i) === 0,
    "Viewer-debug",
  );
  return log;
}
