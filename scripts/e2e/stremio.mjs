// Shared pieces of the Stremio scenarios (stremio-settings, stremio-sources):
// accounts read once and redacted, a report whose every line is scrubbed, the
// Windows vault, PrintWindow captures with the account values masked, and the
// Réglages › Stremio panel.
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { root } from "./app.mjs";
import { debugEnv, libraryNav } from "./scenario.mjs";

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
