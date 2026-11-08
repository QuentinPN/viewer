// npm run release:publish [-- --dry-run] (ADR 0038): after `npm run release`, checks,
// builds and publishes the tagged version. Refuses unless HEAD is the release commit
// on main with its tag vX.Y.Z and no GitHub release exists for it; then fetches
// libmpv, runs verify:web and verify:rust, builds the NSIS installer, pushes main and
// the tag, and creates the GitHub release with the installer and its CHANGELOG.md
// section. --dry-run turns the refusals into warnings and stops after the build,
// printing the notes: nothing is pushed or published.
import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { changelogSection, releaseNotes } from "../src/release/version.ts";

const root = join(import.meta.dirname, "..");
const dryRun = process.argv.includes("--dry-run");
const git = (...args: string[]) =>
  execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
const read = (path: string) => readFileSync(join(root, path), "utf8");

/** Runs a step in the console; stops the script when it fails. */
function step(title: string, command: string, args: string[]) {
  console.log(`\n=== ${title}`);
  const r = spawnSync(command, args, {
    cwd: root,
    stdio: "inherit",
    shell: true,
  });
  if (r.status !== 0) {
    console.error(`échec : ${title} (code ${r.status})`);
    process.exit(1);
  }
}
const powershell = (script: string, ...args: string[]) => [
  "-NoProfile",
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  script,
  ...args,
];

function refuse(reason: string) {
  if (!dryRun) {
    console.error(reason);
    process.exit(1);
  }
  console.warn(`(essai à blanc) ${reason}`);
}

const version = JSON.parse(read("package.json")).version as string;
const tag = `v${version}`;
if (git("status", "--porcelain"))
  refuse("l'arbre de travail a des changements");
if (git("rev-parse", "--abbrev-ref", "HEAD") !== "main")
  refuse("la publication se fait depuis main");
const tagged = git("tag", "--points-at", "HEAD").split("\n");
if (!tagged.includes(tag))
  refuse(`HEAD ne porte pas le tag ${tag} : lancer d'abord npm run release`);
const existing = spawnSync("gh", ["release", "view", tag], { cwd: root });
if (existing.status === 0) refuse(`la release GitHub ${tag} existe déjà`);
const section = changelogSection(read("CHANGELOG.md"), version);
if (!section) {
  console.error(`CHANGELOG.md n'a pas de section ${version}`);
  process.exit(1);
}

step("libmpv", "powershell", powershell("scripts/fetch-libmpv.ps1"));
step("verify:web", "npm", ["run", "verify:web"]);
step(
  "verify:rust",
  "powershell",
  powershell("scripts/with-msvc.ps1", "npm", "run", "verify:rust"),
);
step(
  "installateur",
  "powershell",
  powershell("scripts/with-msvc.ps1", "npx", "tauri", "build"),
);

const name = `Viewer_${version}_x64-setup.exe`;
const installer = join(root, "src-tauri/target/release/bundle/nsis", name);
const sha256 = createHash("sha256")
  .update(readFileSync(installer))
  .digest("hex");
const notes = releaseNotes(version, section, {
  name,
  bytes: statSync(installer).size,
  sha256,
});
if (dryRun) {
  console.log(
    `\n=== essai à blanc : ni push ni release\n${installer}\n\n${notes}`,
  );
  process.exit(0);
}

step("push", "git", ["push", "origin", "main", tag]);
const notesFile = join(tmpdir(), `viewer-${tag}-notes.md`);
writeFileSync(notesFile, notes);
step("release GitHub", "gh", [
  "release",
  "create",
  tag,
  `"${installer}"`,
  "--title",
  `"Viewer ${version}"`,
  "--notes-file",
  `"${notesFile}"`,
  "--verify-tag",
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
    `l'installateur en ligne ne correspond pas : ${uploaded?.digest ?? "absent"}`,
  );
  process.exit(1);
}
console.log(
  `\n${tag} publiée : ${assets.url}\nSHA-256 ${sha256} vérifié en ligne.`,
);
