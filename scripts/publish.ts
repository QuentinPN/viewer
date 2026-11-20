// node scripts/publish.ts [--dry-run] (ADR 0106, 0126): run by the `desktop` job of
// .gitea/workflows/release.yml on the tag vX.Y.Z made by the release action. Fetches
// libmpv, runs verify:web and verify:rust, builds the signed NSIS installer, creates
// the release with the installer and its CHANGELOG.md section on the public releases
// repository (ADR 0111), then rewrites desktop/latest.json there for the auto-updater
// (ADR 0112). --dry-run stops after the build and prints the notes: nothing is published.
import { existsSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { desktopManifest } from "../src/release/manifest.ts";
import { changelogSection, releaseNotes } from "../src/release/version.ts";
import { gitea } from "./gitea.ts";
import { dryRun, fail, powershell, read, root, sha256, step } from "./step.ts";

const version = JSON.parse(read("package.json")).version as string;
const tag = `v${version}`;
const releases = dryRun ? undefined : gitea(root);
const section =
  changelogSection(read("CHANGELOG.md"), version) ??
  fail(`CHANGELOG.md n'a pas de section ${version}`);

// Updater signing key (ADR 0112), outside the repository, without password
const signingKey =
  process.env.TAURI_SIGNING_PRIVATE_KEY ??
  join(homedir(), ".viewer", "updater.key");
if (!process.env.TAURI_SIGNING_PRIVATE_KEY && !existsSync(signingKey))
  fail(`clé de signature des mises à jour absente : ${signingKey}`);
process.env.TAURI_SIGNING_PRIVATE_KEY = signingKey;
process.env.TAURI_SIGNING_PRIVATE_KEY_PASSWORD ??= "";

powershell("libmpv", "fetch-libmpv.ps1");
step("verify:web", "npm", ["run", "verify:web"]);
powershell("verify:rust", "with-msvc.ps1", "npm", "run", "verify:rust");
powershell("installateur", "with-msvc.ps1", "npx", "tauri", "build");

const name = `Viewer_${version}_x64-setup.exe`;
const installer = join(root, "src-tauri/target/release/bundle/nsis", name);
const hash = sha256(installer);
const signature = readFileSync(`${installer}.sig`, "utf8");
const notes = releaseNotes(version, section, {
  name,
  bytes: statSync(installer).size,
  sha256: hash,
});
if (!releases) {
  console.log(
    `\n=== essai à blanc : rien n'est publié\n${installer}\n\n${notes}`,
  );
  process.exit(0);
}

const release = await releases.publish(
  tag,
  `Viewer ${version}`,
  notes,
  installer,
  hash,
);
await releases.writeFile(
  "desktop/latest.json",
  `${JSON.stringify(desktopManifest(version, section, release.fileUrl, signature, new Date()), null, 2)}\n`,
  `Viewer ${version}`,
);
console.log(
  `\n${tag} publiée : ${release.url}\nSHA-256 ${hash} vérifié en ligne.\nManifeste ${releases.page}/raw/branch/main/desktop/latest.json à jour.`,
);
