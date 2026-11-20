// node scripts/publish-android.ts [--dry-run] (ADR 0107, 0126): run by the `android`
// job of .gitea/workflows/release.yml on the tag android-vX.Y.Z made by the release
// action. Fetches libmpv, runs the unit tests, the release build and its lint, creates
// the release android-vX.Y.Z with the APK on the public releases repository (ADR 0111),
// then rewrites android/latest.json there for the in-app updater (ADR 0112).
// --dry-run stops after the build and prints the notes: nothing is published.
import { copyFileSync, existsSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { androidManifest } from "../src/release/manifest.ts";
import {
  androidReleaseNotes,
  changelogSection,
  gradleVersion,
} from "../src/release/version.ts";
import { gitea } from "./gitea.ts";
import { dryRun, fail, powershell, read, root, sha256, step } from "./step.ts";

const app = join(root, "android-tv");
const version =
  gradleVersion(read("android-tv/app/build.gradle.kts")) ??
  fail("versionName ou versionCode introuvable dans build.gradle.kts");
const tag = `android-v${version.name}`;
const releases = dryRun ? undefined : gitea(root);
if (await releases?.exists(tag)) fail(`la release ${tag} existe déjà`);
const section =
  changelogSection(read("android-tv/CHANGELOG.md"), version.name) ??
  fail(`android-tv/CHANGELOG.md n'a pas de section ${version.name}`);

powershell("libmpv Android", "fetch-libmpv-android.ps1");
const studioJdk = "C:\\Program Files\\Android\\Android Studio\\jbr";
// Gradle needs JDK 17+: Android Studio's, even over an older JAVA_HOME
if (existsSync(studioJdk)) process.env.JAVA_HOME = studioJdk;
step(
  "tests, build release et lint",
  `"${join(app, "gradlew.bat")}"`,
  ["-q", "testDebugUnitTest", "assembleRelease", "lintRelease"],
  app,
);

const name = `Viewer-TV_${version.name}.apk`;
const apk = join(tmpdir(), name);
copyFileSync(join(app, "app/build/outputs/apk/release/app-release.apk"), apk);
const hash = sha256(apk);
const bytes = statSync(apk).size;
const notes = androidReleaseNotes(version, section, {
  name,
  bytes,
  sha256: hash,
});
if (!releases) {
  console.log(`\n=== essai à blanc : rien n'est publié\n${apk}\n\n${notes}`);
  process.exit(0);
}

const release = await releases.publish(
  tag,
  `Viewer Android TV ${version.name}`,
  notes,
  apk,
  hash,
);
await releases.writeFile(
  "android/latest.json",
  `${JSON.stringify(androidManifest(version, section, release.fileUrl, { bytes, sha256: hash }), null, 2)}\n`,
  `Viewer Android TV ${version.name}`,
);
console.log(
  `\n${tag} publiée : ${release.url}\nSHA-256 ${hash} vérifié en ligne.\nManifeste ${releases.page}/raw/branch/main/android/latest.json à jour.`,
);
