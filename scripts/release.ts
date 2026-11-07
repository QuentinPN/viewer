// npm run release -- <patch|minor|major|X.Y.Z> (ADR 0032): writes the version to
// package.json, package-lock.json, Cargo.toml and Cargo.lock, adds a CHANGELOG.md
// section from the commits since the last tag, commits and tags vX.Y.Z. Never
// pushes. Node runs this TypeScript directly (type stripping).
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  addChangelogSection,
  nextVersion,
  setCargoLockVersion,
  setCargoTomlVersion,
  setPackageVersion,
} from "../src/release/version.ts";

const root = join(import.meta.dirname, "..");
const git = (...args: string[]) =>
  execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
const read = (path: string) => readFileSync(join(root, path), "utf8");
const write = (path: string, text: string) =>
  writeFileSync(join(root, path), text);

const target = process.argv[2];
if (!target) {
  console.error("usage: npm run release -- <patch|minor|major|X.Y.Z>");
  process.exit(2);
}
if (git("status", "--porcelain")) {
  console.error("the working tree has changes: commit or set them aside first");
  process.exit(1);
}

const current = JSON.parse(read("package.json")).version as string;
const version = nextVersion(current, target);
const tag = `v${version}`;
if (git("tag", "--list", tag)) {
  console.error(`the tag ${tag} already exists`);
  process.exit(1);
}

let previous = "";
try {
  previous = git("describe", "--tags", "--abbrev=0", "--match", "v*");
} catch {
  // No tag yet: the section lists every commit.
}
const range = previous ? [`${previous}..HEAD`] : [];
const subjects = git("log", "--no-merges", "--format=%s", ...range)
  .split("\n")
  .filter((s) => s && !s.startsWith("Release v"));

const now = new Date();
const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;

write("package.json", setPackageVersion(read("package.json"), version));
write(
  "package-lock.json",
  setPackageVersion(read("package-lock.json"), version),
);
write(
  "src-tauri/Cargo.toml",
  setCargoTomlVersion(read("src-tauri/Cargo.toml"), version),
);
write(
  "src-tauri/Cargo.lock",
  setCargoLockVersion(read("src-tauri/Cargo.lock"), "viewer", version),
);
const changelog = existsSync(join(root, "CHANGELOG.md"))
  ? read("CHANGELOG.md")
  : "";
write("CHANGELOG.md", addChangelogSection(changelog, version, date, subjects));

git(
  "add",
  "package.json",
  "package-lock.json",
  "src-tauri/Cargo.toml",
  "src-tauri/Cargo.lock",
  "CHANGELOG.md",
);
git("commit", "-q", "-m", `Release ${tag}`);
git("tag", "-a", tag, "-m", `Viewer ${version}`);
console.log(
  `${current} → ${version}: commit and tag ${tag} created (not pushed).`,
);
console.log(
  `${subjects.length} commit(s) since ${previous || "the start"} in CHANGELOG.md.`,
);
