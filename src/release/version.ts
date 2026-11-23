// Version and changelog readers of the CI publish scripts. The release
// action writes versions and changelogs; these pure helpers read them back.

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

/** Notes of the GitHub release: changelog section, then the installer. */
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

/** `versionName` and `versionCode` of the Android TV app's build.gradle.kts. */
export function gradleVersion(
  kts: string,
): { name: string; code: number } | undefined {
  const name = kts.match(/^\s*versionName\s*=\s*"([^"]+)"/m)?.[1];
  const code = kts.match(/^\s*versionCode\s*=\s*(\d+)/m)?.[1];
  return name && code ? { name, code: Number(code) } : undefined;
}

/** Notes of an Android TV GitHub release: changelog section, then the APK. */
export function androidReleaseNotes(
  version: { name: string; code: number },
  section: string,
  apk: { name: string; bytes: number; sha256: string },
): string {
  const mib = (apk.bytes / 1024 / 1024).toFixed(2).replace(".", ",");
  return [
    `## Viewer Android TV ${version.name}`,
    "",
    section,
    "",
    `APK pour la Shield TV (arm64-v8a, Android 9 ou plus, code de version ${version.code}), signé avec la clé de debug : à installer par \`adb install -r\`, il remplace la version déjà installée.`,
    "",
    `\`${apk.name}\` — ${mib} Mio — SHA-256 \`${apk.sha256.toLowerCase()}\``,
    "",
  ].join("\n");
}
