// Version bumps for `scripts/release.ts` (ADR 0032). Pure text transforms, so
// Vitest covers them; the script does the file and git work.

export type Bump = "patch" | "minor" | "major";

const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

function parts(version: string): [number, number, number] {
  const m = SEMVER.exec(version);
  if (!m) throw new Error(`not a X.Y.Z version: ${version}`);
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

/** -1, 0 or 1 as `a` is lower than, equal to or higher than `b`. */
export function compareVersions(a: string, b: string): number {
  const [pa, pb] = [parts(a), parts(b)];
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return Math.sign(d);
  }
  return 0;
}

/** The next version: a bump kind, or an explicit X.Y.Z that must be higher. */
export function nextVersion(current: string, target: string): string {
  const [major, minor, patch] = parts(current);
  let next: string;
  if (target === "patch") next = `${major}.${minor}.${patch + 1}`;
  else if (target === "minor") next = `${major}.${minor + 1}.0`;
  else if (target === "major") next = `${major + 1}.0.0`;
  else next = target;
  if (compareVersions(next, current) <= 0)
    throw new Error(`${next} is not higher than ${current}`);
  return next;
}

function replaceOnce(
  text: string,
  pattern: RegExp,
  replacement: string,
  what: string,
) {
  if (!pattern.test(text)) throw new Error(`no version found in ${what}`);
  return text.replace(pattern, replacement);
}

/** The `[package]` version of a Cargo.toml (the first `version =` line). */
export function cargoTomlVersion(toml: string): string | undefined {
  return /^version = "([^"]+)"/m.exec(toml)?.[1];
}

export function setCargoTomlVersion(toml: string, version: string): string {
  return replaceOnce(
    toml,
    /^version = "[^"]+"/m,
    `version = "${version}"`,
    "Cargo.toml",
  );
}

/** The version of package `name` in a Cargo.lock. */
export function cargoLockVersion(
  lock: string,
  name: string,
): string | undefined {
  return new RegExp(
    `\\[\\[package\\]\\]\\r?\\nname = "${name}"\\r?\\nversion = "([^"]+)"`,
  ).exec(lock)?.[1];
}

export function setCargoLockVersion(
  lock: string,
  name: string,
  version: string,
): string {
  return replaceOnce(
    lock,
    new RegExp(
      `(\\[\\[package\\]\\]\\r?\\nname = "${name}"\\r?\\nversion = )"[^"]+"`,
    ),
    `$1"${version}"`,
    "Cargo.lock",
  );
}

/** package.json or package-lock.json with the root package at `version`. */
export function setPackageVersion(json: string, version: string): string {
  const data = JSON.parse(json);
  data.version = version;
  if (data.packages?.[""]) data.packages[""].version = version;
  return `${JSON.stringify(data, null, 2)}\n`;
}

/** CHANGELOG.md with a section for `version` above the previous ones. */
export function addChangelogSection(
  changelog: string,
  version: string,
  date: string,
  subjects: string[],
): string {
  const header = "# Journal des versions\n";
  const body = changelog.startsWith(header)
    ? changelog.slice(header.length)
    : changelog;
  const items = subjects.length
    ? subjects.map((s) => `- ${s}`).join("\n")
    : "- Aucun changement.";
  return `${header}\n## ${version} — ${date}\n\n${items}\n${body.replace(/^\n*/, "\n")}`.replace(
    /\n+$/,
    "\n",
  );
}

/** Body of the CHANGELOG.md section of `version`, without its heading; null when absent. */
export function changelogSection(
  changelog: string,
  version: string,
): string | null {
  const lines = changelog.replace(/\r\n/g, "\n").split("\n");
  const start = lines.findIndex((l) => l.startsWith(`## ${version} `));
  if (start < 0) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((l) => l.startsWith("## "));
  return (end < 0 ? rest : rest.slice(0, end)).join("\n").trim();
}

/** Notes of the GitHub release (ADR 0106): changelog section, then the installer. */
export function releaseNotes(
  version: string,
  section: string,
  installer: { name: string; bytes: number; sha256: string },
): string {
  const mib = (installer.bytes / 1024 / 1024).toFixed(2).replace(".", ",");
  return [
    `## Viewer ${version}`,
    "",
    section,
    "",
    "Installateur Windows par utilisateur (NSIS), remplace la version déjà installée. Non signé : SmartScreen peut avertir au premier lancement.",
    "",
    `\`${installer.name}\` — ${mib} Mio — SHA-256 \`${installer.sha256.toLowerCase()}\``,
    "",
  ].join("\n");
}
