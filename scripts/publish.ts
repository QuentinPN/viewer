// node scripts/publish.ts [--dry-run]: run by the `desktop` job of
// .gitea/workflows/release.yml on the tag vX.Y.Z made by the release action. Fetches
// libmpv, runs verify:web and the Go gates, builds the Go/Wails release exe and its
// NSIS installer, then signs the installer with ed25519 (src/release/signature.ts).
// It creates the release with the installer and its CHANGELOG.md section on the
// public releases repository, then rewrites desktop/latest.json there for the
// app's updater. --dry-run stops after the signature, writes the manifest next to
// the installer (file:// url) and prints the notes: nothing is published.
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { desktopManifest } from "../src/release/manifest.ts";
import {
  publicKeyOf,
  signUpdate,
  verifyUpdate,
} from "../src/release/signature.ts";
import { changelogSection, releaseNotes } from "../src/release/version.ts";
import { gitea } from "./gitea.ts";
import {
  dryRun,
  fail,
  goGates,
  powershell,
  read,
  root,
  sha256,
  step,
} from "./step.ts";

const version = JSON.parse(read("package.json")).version as string;
const tag = `v${version}`;
const releases = dryRun ? undefined : gitea(root);
const section =
  changelogSection(read("CHANGELOG.md"), version) ??
  fail(`CHANGELOG.md n'a pas de section ${version}`);

// Signing key, outside the repository: ed25519, PKCS#8 PEM whose public half is
// go-app/build/updater-ed25519.pub.
const ed25519Key = join(homedir(), ".viewer", "updater-ed25519.key");
if (!existsSync(ed25519Key))
  fail(`clé de signature ed25519 absente : ${ed25519Key}`);
const ed25519Pem = readFileSync(ed25519Key, "utf8");
const ed25519Pub = read("go-app/build/updater-ed25519.pub").trim();
if ((await publicKeyOf(ed25519Pem)) !== ed25519Pub)
  fail(`${ed25519Key} ne correspond pas à go-app/build/updater-ed25519.pub`);

powershell("libmpv", "fetch-libmpv.ps1");
step("verify:web", "npm", ["run", "verify:web"]);

goGates();

// Frontend, exe (scripts/build-wails-exe.ts) and installer, rebuilt every time
const outDir = join(root, "go-app", "bin");
const name = `Viewer_${version}_x64-setup.exe`;
const installer = join(outDir, name);
rmSync(installer, { force: true });
powershell(
  "installateur",
  "build-wails-installer.ps1",
  "-Build",
  "-OutDir",
  outDir,
);
const bytes = readFileSync(installer);

console.log("\n=== signature ed25519");
const ed25519 = await signUpdate(version, bytes, ed25519Pem);
if (!(await verifyUpdate(version, bytes, ed25519, ed25519Pub)))
  fail("signature ed25519 refusée par la clé publique versionnée");

const hash = sha256(installer);
const notes = releaseNotes(version, section, {
  name,
  bytes: bytes.length,
  sha256: hash,
});
const manifest = (url: string) =>
  `${JSON.stringify(desktopManifest(version, section, url, ed25519, new Date()), null, 2)}\n`;
if (!releases) {
  const local = join(outDir, "latest.json");
  writeFileSync(local, manifest(pathToFileURL(installer).href));
  console.log(
    `\n=== essai à blanc : rien n'est publié\n${installer}\n${local}\nSignature ed25519 vérifiée.\n\n${notes}`,
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
  manifest(release.fileUrl),
  `Viewer ${version}`,
);
console.log(
  `\n${tag} publiée : ${release.url}\nSHA-256 ${hash} vérifié en ligne.\nManifeste ${releases.page}/raw/branch/main/desktop/latest.json à jour.`,
);
