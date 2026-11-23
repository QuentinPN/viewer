// Réglages › Stremio on the Go + Wails debug build, driven over CDP like
// wails-smoke (no OS input, no screen capture: the window stays off-screen and
// is captured with PrintWindow on its own HWND, print-window.ps1).
//
// Steps: first run → Emby sign-in with the debug.env account → Réglages from the
// navigation → the « Stremio » panel right after « Demandes », signed out, with
// e-mail and password fields → Tab through the panel, visible focus → wrong
// credentials, French error, password field emptied, still signed out → real
// sign-in (only with ST_EMAIL_FILE and ST_PASS_FILE) → « Connecté » and the
// expected stream addons, no addon address nor authKey in the page → sign-out
// from the button, no `stremio` vault entry left → Emby sign-out. The app log is
// checked for addon addresses, the authKey and the account values.
//
// The UI is driven with Playwright locators and keys (CDP Input events into the
// page); window.__viewer.invoke only signs out of Stremio in the cleanup, when the
// UI failed. The Stremio e-mail and password are read from the files named by
// ST_EMAIL_FILE / ST_PASS_FILE and the Emby account from debug.env: none of them
// reaches the console, report.json, a file name or a capture (masked on screen).
// The authKey is read from the vault once signed in, kept in memory only, to
// look for it in the page and the log.
//
// The app runs with APPDATA, LOCALAPPDATA and its log in a temporary folder,
// deleted at the end; the Windows vault is shared, so the run refuses to start
// over an existing Viewer-debug or stremio entry and always ends signed out.
//
//   node scripts/e2e/stremio-settings.mjs [--build] [--out <dir>]
//
// --build  npm run build, then a debug `go build` (app.mjs buildDebug)
// --out    folder of the captures and report.json
//          (default: <tmp>/viewer-stremio-settings-<timestamp>)
import { existsSync, mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildDebug, debugExe as exe, launch } from "./app.mjs";
import {
  libraryNav,
  signIn,
  signInHeading,
  signOut,
  startScreen,
} from "./scenario.mjs";
import {
  capture,
  emailField,
  openSettings,
  settingsPanel as panel,
  passwordField,
  readAccounts,
  readAuthKey,
  removeWork,
  scrubbedReport,
  scrubber,
  settingsButton,
  settingsState as stateText,
  vaultTargets,
  waitForSettingsState as waitForState,
} from "./stremio.mjs";

const argv = process.argv.slice(2);
const outArg = argv.indexOf("--out");
const stamp = new Date()
  .toISOString()
  .replace(/[-:]/g, "")
  .replace("T", "-")
  .slice(0, 15);
const out =
  outArg >= 0
    ? argv[outArg + 1]
    : join(tmpdir(), `viewer-stremio-settings-${stamp}`);
const PORT = 9343;
const WRONG = { email: "nobody@example.com", password: "wrong" };
const WRONG_MESSAGE = "Adresse e-mail ou mot de passe incorrect.";
const ADDONS = [
  "Lumio · Profil",
  "AIOSources",
  "StreamFusionReborn",
  "Wastream | AD",
  "Comet | AD",
  "TorrentsDB AD",
];

mkdirSync(out, { recursive: true });
const work = mkdtempSync(join(tmpdir(), "viewer-e2e-stremio-"));
const logFile = join(work, "logs", "viewer.log");
const appEnv = {
  APPDATA: join(work, "Roaming"),
  LOCALAPPDATA: join(work, "Local"),
  VIEWER_E2E_LOG_DIR: join(work, "logs"),
};

// ---- accounts and redaction -------------------------------------------------

const { account, stremio, secrets } = readAccounts();
const { scrub, firstLine } = scrubber(secrets);
const { check, skip, finish } = scrubbedReport(scrub, {
  build: "debug (coffre Viewer-debug)",
  realSignIn: Boolean(stremio),
});

// ---- panel helpers ------------------------------------------------------------

/**
 * Tab from the panel heading until the focus leaves the panel; every control
 * reached must match :focus-visible and show an outline or box-shadow that it
 * has not while blurred.
 */
async function tabThrough(page) {
  await page.evaluate(() => {
    const p = document.querySelector(
      "section[aria-labelledby=settings-stremio]",
    );
    const style = (el) => {
      const s = getComputedStyle(el);
      return `${s.outlineStyle} ${s.outlineWidth} ${s.outlineColor} | ${s.boxShadow}`;
    };
    p.querySelector("h2").focus();
    window.__blurred = new Map(
      [...p.querySelectorAll("input, button")].map((el) => [el, style(el)]),
    );
  });
  const reached = [];
  for (let i = 0; i < 8; i++) {
    await page.keyboard.press("Tab");
    const r = await page.evaluate(() => {
      const p = document.querySelector(
        "section[aria-labelledby=settings-stremio]",
      );
      const el = document.activeElement;
      if (!el || !p.contains(el)) return null;
      const s = getComputedStyle(el);
      const now = `${s.outlineStyle} ${s.outlineWidth} ${s.outlineColor} | ${s.boxShadow}`;
      const outline =
        s.outlineStyle !== "none" && parseFloat(s.outlineWidth) > 0;
      const shadow = s.boxShadow !== "none";
      return {
        what: el.id || el.textContent.trim() || el.tagName,
        visible:
          el.matches(":focus-visible") &&
          (outline || shadow) &&
          now !== window.__blurred.get(el),
      };
    });
    if (!r) break;
    reached.push(r);
  }
  return reached;
}

/** Page text and markup: no addon address, no `transportUrl`, no authKey. */
async function domLeaks(page, authKey) {
  // The markup holds every text node and attribute; field values are properties.
  const dom = await page.evaluate(() =>
    [
      document.documentElement.outerHTML,
      ...[...document.querySelectorAll("input")].map((i) => i.value),
    ].join("\n"),
  );
  return {
    manifest: /manifest\.json/i.test(dom),
    transportUrl: /transportUrl/i.test(dom),
    authKey: authKey ? dom.includes(authKey) : null,
  };
}

// ---- run ----------------------------------------------------------------------

let app = null;
let authKey = null;
/** What the cleanup must undo if a step fails. `started`: the vault was empty
 * of Viewer-debug and stremio entries, so any entry left is this run's. */
const state = { started: false, emby: false, stremio: false };

async function cleanup() {
  // Stremio first: its key must not outlive the run.
  if (state.stremio || vaultTargets(/stremio/i) > 0) {
    try {
      if (!app || app.proc.exitCode !== null)
        app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
      await app.page.evaluate(() => window.__viewer.invoke("stremio_sign_out"));
      state.stremio = false;
      check("cleanup_stremio_sign_out", true, "par window.__viewer.invoke");
    } catch (e) {
      check("cleanup_stremio_sign_out", false, firstLine(e));
    }
  }
  if (state.emby || vaultTargets(/Viewer-debug/i) > 0) {
    try {
      if (!app || app.proc.exitCode !== null)
        app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
      if ((await startScreen(app.page)) === "signedIn") await signOut(app.page);
      state.emby = false;
      check("cleanup_emby_sign_out", true);
    } catch (e) {
      check("cleanup_emby_sign_out", false, firstLine(e));
    }
  }
}

try {
  if (vaultTargets(/Viewer-debug|stremio/i) > 0)
    throw new Error(
      "le coffre a déjà une entrée Viewer-debug ou stremio : rien n'est lancé",
    );
  state.started = true;
  if (argv.includes("--build")) buildDebug();
  if (!existsSync(exe)) throw new Error(`build absent : ${exe} (--build)`);

  app = await launch({ exe, port: PORT, env: appEnv, timeoutMs: 30000 });
  const { page } = app;
  // The window is off-screen and never in the foreground: without focus
  // emulation, :focus-visible would not match.
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });

  // Emby sign-in, then Réglages from the navigation.
  check("first_run_signed_out", (await startScreen(page)) === "signedOut");
  await signIn(page, account, account.password);
  state.emby = true;
  await libraryNav(page).waitFor({ timeout: 30000 });
  await openSettings(page);

  // 1. The panel, after « Demandes », signed out, with its two fields.
  await waitForState(page, "Déconnecté", 20000);
  const order = await page.evaluate(() => {
    const st = document.querySelector(
      "section[aria-labelledby=settings-stremio]",
    );
    const req = document.querySelector(
      "section[aria-labelledby=settings-requests]",
    );
    return {
      title: st?.querySelector("h2")?.textContent?.trim(),
      requests: req?.querySelector("h2")?.textContent?.trim(),
      next: req?.nextElementSibling === st,
    };
  });
  check(
    "panel_after_demandes",
    order.title === "Stremio" && order.requests === "Demandes" && order.next,
    `« ${order.requests} » puis « ${order.title} », adjacents : ${order.next}`,
  );
  check("state_signed_out", (await stateText(page)) === "Déconnecté");
  const fields = {
    email: await emailField(page).getAttribute("type"),
    password: await passwordField(page).getAttribute("type"),
  };
  check(
    "email_and_password_fields",
    fields.email === "email" && fields.password === "password",
    `e-mail type=${fields.email}, mot de passe type=${fields.password}`,
  );

  // 4a. Keyboard, signed out: the submit button is enabled once both are filled.
  await emailField(page).fill(WRONG.email);
  await passwordField(page).fill(WRONG.password);
  const outTabs = await tabThrough(page);
  check(
    "focus_visible_signed_out",
    outTabs.length >= 3 && outTabs.every((t) => t.visible),
    outTabs.map((t) => `${t.what}:${t.visible}`).join(", "),
  );

  // 2. Wrong credentials.
  await settingsButton(page, "Se connecter").click();
  const alert = panel(page).getByRole("alert");
  await alert.waitFor({ timeout: 30000 });
  await waitForState(page, "Déconnecté");
  const message = (await alert.textContent())?.trim();
  check("wrong_credentials_message", message === WRONG_MESSAGE, message);
  check(
    "wrong_credentials_password_cleared",
    (await passwordField(page).inputValue()) === "",
  );
  check(
    "wrong_credentials_still_signed_out",
    (await stateText(page)) === "Déconnecté" && vaultTargets(/stremio/i) === 0,
  );

  if (!stremio) {
    for (const name of [
      "real_sign_in",
      "addons_listed",
      "focus_visible_signed_in",
      "sign_out_button",
    ])
      skip(name, "ST_EMAIL_FILE et ST_PASS_FILE absents");
    await emailField(page).fill("");
    await panel(page).scrollIntoViewIfNeeded();
    const c = await capture(app, out, "stremio-deconnecte", secrets);
    check(
      "capture_signed_out",
      c.printed && c.distinctColors > 20,
      `${c.file}, ${c.width}×${c.height}, ${c.distinctColors} couleurs`,
    );
  } else {
    // 3. Real sign-in.
    await emailField(page).fill(stremio.email);
    await passwordField(page).fill(stremio.password);
    await settingsButton(page, "Se connecter").click();
    state.stremio = true;
    await waitForState(page, "Connecté", 45000);
    check("real_sign_in", (await stateText(page)) === "Connecté");
    authKey = readAuthKey();
    check("auth_key_in_vault", authKey !== null);
    if (authKey) secrets.push(authKey);
    const rows = (
      await panel(page).locator("ul.addons li").allTextContents()
    ).map((t) => t.replace(/\s+/g, " ").trim());
    const missing = ADDONS.filter(
      (n) => !rows.includes(`${n} — Films · Séries`),
    );
    check(
      "addons_listed",
      missing.length === 0,
      missing.length
        ? `absents : ${missing.join(", ")} ; lignes : ${rows.join(" / ")}`
        : `${rows.length} lignes : ${rows.join(" / ")}`,
    );
    const leaks = await domLeaks(page, authKey);
    check(
      "dom_without_addresses_or_key",
      !leaks.manifest && !leaks.transportUrl && leaks.authKey === false,
      `manifest.json ${leaks.manifest}, transportUrl ${leaks.transportUrl}, authKey ${leaks.authKey}`,
    );

    // 4b. Keyboard, signed in.
    const inTabs = await tabThrough(page);
    check(
      "focus_visible_signed_in",
      inTabs.length >= 1 && inTabs.every((t) => t.visible),
      inTabs.map((t) => `${t.what}:${t.visible}`).join(", "),
    );

    const pwd = await passwordField(page).count();
    const pwdEmpty =
      pwd === 0 || (await passwordField(page).inputValue()) === "";
    check("password_empty_at_capture", pwdEmpty, `champ présent : ${pwd > 0}`);
    await panel(page).scrollIntoViewIfNeeded();
    if (pwdEmpty) {
      const c = await capture(app, out, "stremio-connecte", secrets);
      check(
        "capture_signed_in",
        c.printed && c.distinctColors > 20,
        `${c.file}, ${c.width}×${c.height}, ${c.distinctColors} couleurs`,
      );
    }

    // 5. Sign-out from the button.
    await settingsButton(page, "Se déconnecter").click();
    await waitForState(page, "Déconnecté");
    state.stremio = false;
    check("sign_out_button", (await stateText(page)) === "Déconnecté");
    const leftAfter = vaultTargets(/stremio/i) > 0;
    check(
      "vault_without_stremio_after_sign_out",
      !leftAfter,
      String(leftAfter),
    );
    const after = await domLeaks(page, authKey);
    check(
      "dom_without_key_after_sign_out",
      !after.manifest && !after.transportUrl && after.authKey === false,
    );
    // 6. The signed-out panel.
    await panel(page).scrollIntoViewIfNeeded();
    const c = await capture(app, out, "stremio-deconnecte", secrets);
    check(
      "capture_signed_out",
      c.printed && c.distinctColors > 20,
      `${c.file}, ${c.width}×${c.height}, ${c.distinctColors} couleurs`,
    );
  }

  await signOut(page);
  state.emby = false;
  check("emby_signed_out", await signInHeading(page).isVisible());
} catch (e) {
  check("scenario_error", false, firstLine(e));
} finally {
  // Refused at the start: nothing of this run to undo, the entries are not ours.
  if (state.started) await cleanup();
  if (app) await app.close().catch(() => {});
  app = null;
  // The log, read once the app has exited.
  if (!state.started) {
    // Nothing ran.
  } else if (!existsSync(logFile))
    check("log_written", false, "journal absent");
  else {
    const log = readFileSync(logFile, "utf8");
    check(
      "log_without_addresses_or_key",
      !/manifest\.json/i.test(log) &&
        !/transportUrl/i.test(log) &&
        (!stremio || (authKey !== null && !log.includes(authKey))),
      `manifest.json ${/manifest\.json/i.test(log)}, transportUrl ${/transportUrl/i.test(log)}, authKey ${authKey ? log.includes(authKey) : "non lu"}`,
    );
    const leaked = secrets.filter((s) => log.includes(s)).length;
    check("log_without_account_values", leaked === 0, `${leaked} valeur(s)`);
    // Positive control: the log does record the Stremio actions.
    const wanted = [
      "Stremio sign-in failed",
      ...(stremio ? ["Stremio signed in", "Stremio signed out"] : []),
    ];
    const absent = wanted.filter((w) => !log.includes(w));
    check(
      "log_records_stremio_actions",
      absent.length === 0,
      absent.length ? `absent : ${absent.join(", ")}` : wanted.join(", "),
    );
  }
  if (state.started) {
    const stremioLeft = vaultTargets(/stremio/i) > 0;
    console.log(`cmdkey stremio target present: ${stremioLeft}`);
    check("no_stremio_vault_entry", !stremioLeft, String(stremioLeft));
    check(
      "no_debug_vault_entry",
      vaultTargets(/Viewer-debug/i) === 0,
      "Viewer-debug",
    );
  }
  authKey = null;
  check("work_folder_removed", await removeWork(work));
  finish(out);
}
