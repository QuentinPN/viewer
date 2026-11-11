// npm run android:publish [-- --dry-run] (ADR 0107): checks, builds and publishes the
// Android TV app at the versionName of android-tv/app/build.gradle.kts. Refuses unless
// the tree is clean, HEAD is on main, android-tv/CHANGELOG.md has the version's section
// and no GitHub release exists for android-vX.Y.Z; then fetches libmpv, runs the unit tests, the release
// build and its lint, tags HEAD android-vX.Y.Z, pushes main and the tag, and creates the
// GitHub release with the APK, never marked Latest (that stays the desktop app's).
// --dry-run turns the refusals into warnings and stops after the build, printing the
// notes: nothing is tagged, pushed or published.
import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  copyFileSync,
  existsSync,
  readFileSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  androidReleaseNotes,
  changelogSection,
  gradleVersion,
} from "../src/release/version.ts";

const root = join(import.meta.dirname, "..");
const app = join(root, "android-tv");
const dryRun = process.argv.includes("--dry-run");
const git = (...args: string[]) =>
  execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
const read = (path: string) => readFileSync(join(root, path), "utf8");

/** Runs a step in the console; stops the script when it fails. */
function step(title: string, command: string, args: string[], cwd = root) {
  console.log(`\n=== ${title}`);
  const r = spawnSync(command, args, { cwd, stdio: "inherit", shell: true });
  if (r.status !== 0) {
    console.error(`échec : ${title} (code ${r.status})`);
    process.exit(1);
  }
}

function refuse(reason: string) {
  if (!dryRun) {
    console.error(reason);
    process.exit(1);
  }
  console.warn(`(essai à blanc) ${reason}`);
}

const version = gradleVersion(read("android-tv/app/build.gradle.kts"));
if (!version) {
  console.error("versionName ou versionCode introuvable dans build.gradle.kts");
  process.exit(1);
}
const tag = `android-v${version.name}`;
if (git("status", "--porcelain"))
  refuse("l'arbre de travail a des changements");
if (git("rev-parse", "--abbrev-ref", "HEAD") !== "main")
  refuse("la publication se fait depuis main");
// A tag left by a failed run on this same commit is reused
const tagged = git("tag", "--list", tag);
if (tagged && git("rev-parse", `${tag}^{commit}`) !== git("rev-parse", "HEAD"))
  refuse(`le tag ${tag} existe déjà sur un autre commit : monter versionName`);
const existing = spawnSync("gh", ["release", "view", tag], { cwd: root });
if (existing.status === 0) refuse(`la release GitHub ${tag} existe déjà`);
const section = changelogSection(read("android-tv/CHANGELOG.md"), version.name);
if (!section) {
  console.error(`android-tv/CHANGELOG.md n'a pas de section ${version.name}`);
  process.exit(1);
}

step("libmpv Android", "powershell", [
  "-NoProfile",
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  "scripts/fetch-libmpv-android.ps1",
]);
const studioJdk = "C:\\Program Files\\Android\\Android Studio\\jbr";
if (!process.env.JAVA_HOME && existsSync(studioJdk))
  process.env.JAVA_HOME = studioJdk;
step(
  "tests, build release et lint",
  `"${join(app, "gradlew.bat")}"`,
  ["-q", "testDebugUnitTest", "assembleRelease", "lintRelease"],
  app,
);

const name = `Viewer-TV_${version.name}.apk`;
const apk = join(tmpdir(), name);
copyFileSync(join(app, "app/build/outputs/apk/release/app-release.apk"), apk);
const sha256 = createHash("sha256").update(readFileSync(apk)).digest("hex");
const notes = androidReleaseNotes(version, section, {
  name,
  bytes: statSync(apk).size,
  sha256,
});
if (dryRun) {
  console.log(
    `\n=== essai à blanc : ni tag, ni push, ni release\n${apk}\n\n${notes}`,
  );
  process.exit(0);
}

if (!tagged)
  git("tag", "-a", tag, "-m", `Viewer Android TV ${version.name}`);
step("push", "git", ["push", "origin", "main", tag]);
const notesFile = join(tmpdir(), `viewer-${tag}-notes.md`);
writeFileSync(notesFile, notes);
step("release GitHub", "gh", [
  "release",
  "create",
  tag,
  `"${apk}"`,
  "--title",
  `"Viewer Android TV ${version.name}"`,
  "--notes-file",
  `"${notesFile}"`,
  "--verify-tag",
  "--latest=false",
]);

const assets = JSON.parse(
  execFileSync("gh", ["release", "view", tag, "--json", "url,assets"], {
    cwd: root,
    encoding: "utf8",
  }),
) as { url: string; assets: { name: string; digest: string | null }[] };
const uploaded = assets.assets.find((a) => a.name === name);
if (uploaded?.digest !== `sha256:${sha256}`) {
  console.error(
    `l'APK en ligne ne correspond pas : ${uploaded?.digest ?? "absent"}`,
  );
  process.exit(1);
}
console.log(
  `\n${tag} publiée : ${assets.url}\nSHA-256 ${sha256} vérifié en ligne.`,
);
