// node scripts/publish.ts [--dry-run]: run by the `desktop` job of
// .gitea/workflows/release.yml on the tag vX.Y.Z made by the release action. Fetches
// libmpv, runs verify:web and the Go gates, builds the Go/Wails release exe and its
// NSIS installer, then signs the installer twice: minisign with the Tauri key, for
// the installed Tauri app, and ed25519 (src/release/signature.ts), for the Go app.
// It creates the release with the installer and its CHANGELOG.md section on the
// public releases repository, then rewrites desktop/latest.json there for both
// updaters. --dry-run stops after the signatures, writes the manifest next to the
// installer (file:// url) and prints the notes: nothing is published.
import { spawnSync } from "node:child_process";
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
import { minisignProblem } from "./minisign.ts";
import { dryRun, fail, powershell, read, root, sha256, step } from "./step.ts";

const version = JSON.parse(read("package.json")).version as string;
const tag = `v${version}`;
const releases = dryRun ? undefined : gitea(root);
const section =
  changelogSection(read("CHANGELOG.md"), version) ??
  fail(`CHANGELOG.md n'a pas de section ${version}`);

// Signing keys, outside the repository. minisign: the Tauri updater key, without
// password (TAURI_SIGNING_PRIVATE_KEY holds the key itself when set). ed25519:
// PKCS#8 PEM whose public half is go-app/build/updater-ed25519.pub.
const keys = join(homedir(), ".viewer");
const minisignKey = join(keys, "updater.key");
if (!process.env.TAURI_SIGNING_PRIVATE_KEY && !existsSync(minisignKey))
  fail(`clé de signature minisign absente : ${minisignKey}`);
const ed25519Key = join(keys, "updater-ed25519.key");
if (!existsSync(ed25519Key))
  fail(`clé de signature ed25519 absente : ${ed25519Key}`);
const ed25519Pem = readFileSync(ed25519Key, "utf8");
const ed25519Pub = read("go-app/build/updater-ed25519.pub").trim();
if ((await publicKeyOf(ed25519Pem)) !== ed25519Pub)
  fail(`${ed25519Key} ne correspond pas à go-app/build/updater-ed25519.pub`);
const tauriPubkey = JSON.parse(read("src-tauri/tauri.conf.json")).plugins
  .updater.pubkey as string;

powershell("libmpv", "fetch-libmpv.ps1");
step("verify:web", "npm", ["run", "verify:web"]);

// Go gates, as in TESTING.md, without cgo like the release build
const goApp = join(root, "go-app");
process.env.CGO_ENABLED = "0";
console.log("\n=== gofmt");
const gofmt = spawnSync("gofmt", ["-l", "."], { cwd: goApp, encoding: "utf8" });
if (gofmt.status !== 0 || gofmt.stdout.trim())
  fail(`échec : gofmt\n${gofmt.stdout}${gofmt.stderr}`);
step("go vet", "go", ["vet", "./..."], goApp);
step("go test", "go", ["test", "./..."], goApp);

// Frontend, exe (scripts/build-wails-exe.ts) and installer, rebuilt every time
const outDir = join(goApp, "bin");
const name = `Viewer_${version}_x64-setup.exe`;
const installer = join(outDir, name);
rmSync(installer, { force: true });
rmSync(`${installer}.sig`, { force: true });
powershell(
  "installateur",
  "build-wails-installer.ps1",
  "-Build",
  "-OutDir",
  outDir,
);
const bytes = readFileSync(installer);

console.log("\n=== signature minisign (app Tauri)");
const signer = spawnSync(
  process.execPath,
  [
    join(root, "node_modules/@tauri-apps/cli/tauri.js"),
    "signer",
    "sign",
    ...(process.env.TAURI_SIGNING_PRIVATE_KEY ? [] : ["-f", minisignKey]),
    "-p",
    process.env.TAURI_SIGNING_PRIVATE_KEY_PASSWORD ?? "",
    "--app-version",
    version,
    installer,
  ],
  { cwd: root, stdio: ["ignore", "inherit", "inherit"] },
);
if (signer.status !== 0)
  fail(`échec : tauri signer sign (code ${signer.status})`);
const minisign = readFileSync(`${installer}.sig`, "utf8");
const minisignError = minisignProblem(bytes, minisign, tauriPubkey, version);
if (minisignError) fail(`signature minisign refusée : ${minisignError}`);

console.log("\n=== signature ed25519 (app Go)");
const ed25519 = await signUpdate(version, bytes, ed25519Pem);
if (!(await verifyUpdate(version, bytes, ed25519, ed25519Pub)))
  fail("signature ed25519 refusée par la clé publique versionnée");
const signatures = { minisign, ed25519 };

const hash = sha256(installer);
const notes = releaseNotes(version, section, {
  name,
  bytes: bytes.length,
  sha256: hash,
});
const manifest = (url: string) =>
  `${JSON.stringify(desktopManifest(version, section, url, signatures, new Date()), null, 2)}\n`;
if (!releases) {
  const local = join(outDir, "latest.json");
  writeFileSync(local, manifest(pathToFileURL(installer).href));
  console.log(
    `\n=== essai à blanc : rien n'est publié\n${installer}\n${local}\nSignatures minisign et ed25519 vérifiées.\n\n${notes}`,
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
