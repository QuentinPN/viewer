// Shared steps of the CI publish scripts: publish.ts and
// publish-android.ts run on the Windows runner after the release action has
// bumped the version, written the changelog and tagged the commit. verify-go.ts
// (`npm run verify:go`) runs the Go gates too.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const root = join(import.meta.dirname, "..");
export const dryRun = process.argv.includes("--dry-run");
export const read = (path: string) => readFileSync(join(root, path), "utf8");
export const sha256 = (file: string) =>
  createHash("sha256").update(readFileSync(file)).digest("hex");

/** Stops the script with `reason`. */
export function fail(reason: string): never {
  console.error(reason);
  process.exit(1);
}

/** Runs a step in the console; stops the script when it fails. */
export function step(
  title: string,
  command: string,
  args: string[],
  cwd = root,
) {
  console.log(`\n=== ${title}`);
  const r = spawnSync(command, args, { cwd, stdio: "inherit", shell: true });
  if (r.status !== 0) fail(`échec : ${title} (code ${r.status})`);
}

/** The Go gates of go-app, without cgo like the release build: gofmt lists no
 *  file, then go vet and go test. */
export function goGates() {
  const goApp = join(root, "go-app");
  process.env.CGO_ENABLED = "0";
  console.log("\n=== gofmt");
  const gofmt = spawnSync("gofmt", ["-l", "."], {
    cwd: goApp,
    encoding: "utf8",
  });
  if (gofmt.status !== 0 || gofmt.stdout.trim())
    fail(`échec : gofmt\n${gofmt.stdout}${gofmt.stderr}`);
  step("go vet", "go", ["vet", "./..."], goApp);
  step("go test", "go", ["test", "./..."], goApp);
}

/** Runs a script of `scripts/` with PowerShell 7 (the runner's shell: Windows
 *  PowerShell 5.1 started from it cannot load its own modules). */
export const powershell = (title: string, script: string, ...args: string[]) =>
  step(title, "pwsh", [
    "-NoProfile",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    `scripts/${script}`,
    ...args,
  ]);
