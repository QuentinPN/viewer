// Shared steps of the CI publish scripts (ADR 0126): publish.ts and
// publish-android.ts run on the Windows runner after the release action has
// bumped the version, written the changelog and tagged the commit.
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
