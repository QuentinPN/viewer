// node scripts/build-wails-exe.ts: the release exe go-app/bin/viewer.exe,
// run by `npm run build:wails:exe` after the Wails frontend build. The one place
// that holds the release link flags: no console, release mode, the version of
// package.json and the pinned public key of the update signatures
// (go-app/build/updater-ed25519.pub). Without the key the updater fails closed,
// so the exe is checked to carry it.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fail, read, root } from "./step.ts";

const version = JSON.parse(read("package.json")).version as string;
const publicKey = read("go-app/build/updater-ed25519.pub").trim();
if (!/^[A-Za-z0-9+/]{43}=$/.test(publicKey))
  fail("go-app/build/updater-ed25519.pub : clé publique ed25519 illisible");

const ldflags = [
  "-H=windowsgui",
  "-X viewer/internal/app.buildMode=release",
  `-X viewer/internal/app.version=${version}`,
  `-X viewer/internal/updater.PublicKey=${publicKey}`,
].join(" ");
const exe = join(root, "go-app/bin/viewer.exe");

console.log(`\n=== exe Go ${version} (release)`);
const r = spawnSync(
  "go",
  ["build", "-ldflags", ldflags, "-o", exe, "./cmd/viewer"],
  {
    cwd: join(root, "go-app"),
    stdio: "inherit",
    env: { ...process.env, CGO_ENABLED: "0" },
  },
);
if (r.status !== 0) fail(`échec : go build (code ${r.status})`);
if (!readFileSync(exe).includes(publicKey))
  fail(`${exe} ne contient pas la clé publique des mises à jour`);
