import { describe, expect, it } from "vitest";
import pkg from "../../package.json";
import lock from "../../package-lock.json";
import cargoLock from "../../src-tauri/Cargo.lock?raw";
import cargoToml from "../../src-tauri/Cargo.toml?raw";
import tauriConf from "../../src-tauri/tauri.conf.json";
import {
  addChangelogSection,
  cargoLockVersion,
  cargoTomlVersion,
  changelogSection,
  compareVersions,
  nextVersion,
  releaseNotes,
  setCargoLockVersion,
  setCargoTomlVersion,
  setPackageVersion,
} from "./version";

describe("the version of the repository", () => {
  it("is the same in npm and Cargo files", () => {
    expect(cargoTomlVersion(cargoToml)).toBe(pkg.version);
    expect(cargoLockVersion(cargoLock, "viewer")).toBe(pkg.version);
    expect(lock.version).toBe(pkg.version);
    expect(lock.packages[""].version).toBe(pkg.version);
  });

  it("is read by Tauri from package.json", () => {
    expect(tauriConf.version).toBe("../package.json");
  });
});

describe("nextVersion", () => {
  it("bumps each part and resets the lower ones", () => {
    expect(nextVersion("0.3.1", "patch")).toBe("0.3.2");
    expect(nextVersion("0.3.1", "minor")).toBe("0.4.0");
    expect(nextVersion("0.3.1", "major")).toBe("1.0.0");
  });

  it("takes an explicit version only when it is higher", () => {
    expect(nextVersion("0.1.0", "0.3.1")).toBe("0.3.1");
    expect(() => nextVersion("0.3.1", "0.3.1")).toThrow("not higher");
    expect(() => nextVersion("0.3.1", "0.2.9")).toThrow("not higher");
    expect(() => nextVersion("0.3.1", "v0.4.0")).toThrow("X.Y.Z");
  });

  it("compares numerically, not as text", () => {
    expect(compareVersions("0.10.0", "0.9.0")).toBe(1);
    expect(compareVersions("1.0.0", "1.0.0")).toBe(0);
  });
});

describe("file updates", () => {
  it("changes the package version of Cargo.toml only", () => {
    const toml =
      '[package]\nname = "app"\nversion = "0.1.0"\n\n[dependencies]\nx = { version = "1.0.0" }\n';
    const out = setCargoTomlVersion(toml, "0.3.1");
    expect(cargoTomlVersion(out)).toBe("0.3.1");
    expect(out).toContain('x = { version = "1.0.0" }');
  });

  it("changes only the named package of Cargo.lock, with CRLF too", () => {
    const lockText =
      '[[package]]\r\nname = "other"\r\nversion = "0.1.0"\r\n\r\n[[package]]\r\nname = "viewer"\r\nversion = "0.1.0"\r\n';
    const out = setCargoLockVersion(lockText, "viewer", "0.3.1");
    expect(cargoLockVersion(out, "viewer")).toBe("0.3.1");
    expect(cargoLockVersion(out, "other")).toBe("0.1.0");
    expect(() => setCargoLockVersion(lockText, "missing", "1.0.0")).toThrow(
      "Cargo.lock",
    );
  });

  it("sets the root package of package-lock.json, not its dependencies", () => {
    const json = JSON.stringify({
      version: "0.1.0",
      packages: {
        "": { version: "0.1.0" },
        "node_modules/x": { version: "0.1.0" },
      },
    });
    const out = JSON.parse(setPackageVersion(json, "0.3.1"));
    expect(out.version).toBe("0.3.1");
    expect(out.packages[""].version).toBe("0.3.1");
    expect(out.packages["node_modules/x"].version).toBe("0.1.0");
  });

  it("puts the new changelog section first", () => {
    const first = addChangelogSection("", "0.3.0", "2026-11-07", ["Redesign"]);
    expect(first).toBe(
      "# Journal des versions\n\n## 0.3.0 — 2026-11-07\n\n- Redesign\n",
    );
    const second = addChangelogSection(first, "0.3.1", "2026-11-08", []);
    expect(second).toBe(
      "# Journal des versions\n\n## 0.3.1 — 2026-11-08\n\n- Aucun changement.\n\n## 0.3.0 — 2026-11-07\n\n- Redesign\n",
    );
  });
});

describe("release notes", () => {
  const changelog =
    "# Journal des versions\r\n\r\n## 1.0.1 — 2026-11-08\r\n\r\n- Fix A\r\n- Fix B\r\n\r\n## 1.0.0 — 2026-11-07\r\n\r\n- Old\r\n";

  it("takes the section of one version only", () => {
    expect(changelogSection(changelog, "1.0.1")).toBe("- Fix A\n- Fix B");
    expect(changelogSection(changelog, "1.0.0")).toBe("- Old");
    expect(changelogSection(changelog, "1.0")).toBeNull();
    expect(changelogSection(changelog, "2.0.0")).toBeNull();
  });

  it("ends with the installer name, size and hash", () => {
    const notes = releaseNotes("1.0.1", "- Fix A", {
      name: "Viewer_1.0.1_x64-setup.exe",
      bytes: 37450769,
      sha256: "B129DD",
    });
    expect(notes.startsWith("## Viewer 1.0.1\n\n- Fix A\n")).toBe(true);
    expect(notes).toContain(
      "`Viewer_1.0.1_x64-setup.exe` — 35,72 Mio — SHA-256 `b129dd`",
    );
  });
});
