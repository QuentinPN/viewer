import { describe, expect, it } from "vitest";
import {
  aboutRows,
  buildType,
  decoderRows,
  displayName,
  gpuRows,
  hdrRows,
  osLabel,
} from "./about";

describe("À propos", () => {
  it("names the build type", () => {
    expect(buildType(true)).toBe("Débogage");
    expect(buildType(false)).toBe("Publication");
  });

  it("describes Windows with its version and build", () => {
    expect(
      osLabel({
        edition: "Windows 11 Pro",
        displayVersion: "23H2",
        build: "22631.4460",
      }),
    ).toBe("Windows 11 Pro 23H2 (build 22631.4460)");
    expect(
      osLabel({
        edition: "Windows 10 Pro",
        displayVersion: null,
        build: "19045",
      }),
    ).toBe("Windows 10 Pro (build 19045)");
    expect(osLabel(null)).toBe("Inconnu");
  });

  it("lists each GPU with its driver when known", () => {
    expect(gpuRows([])).toEqual([
      { label: "Carte graphique", value: "Inconnu" },
    ]);
    expect(
      gpuRows([{ name: "NVIDIA GeForce RTX 3060", driver: "32.0.15.6094" }]),
    ).toEqual([
      {
        label: "Carte graphique",
        value: "NVIDIA GeForce RTX 3060 · pilote 32.0.15.6094",
      },
    ]);
    expect(
      gpuRows([
        { name: "Intel UHD", driver: null },
        { name: "AMD Radeon", driver: "31.0.1" },
      ]).map((r) => r.label),
    ).toEqual(["Carte graphique 1", "Carte graphique 2"]);
  });

  it("tells hardware from software decoding, or Inconnu", () => {
    const rows = decoderRows(["h264", "hevc", "av1"]);
    expect(rows.map((r) => `${r.label}=${r.value}`)).toEqual([
      "H.264=Matériel",
      "HEVC=Matériel",
      "HEVC 10 bits=Logiciel",
      "VP9=Logiciel",
      "VP9 10 bits=Logiciel",
      "AV1=Matériel",
    ]);
    expect(decoderRows(null).every((r) => r.value === "Inconnu")).toBe(true);
  });

  it("names displays like Windows and says whether HDR is on", () => {
    expect(
      hdrRows([
        { name: "\\\\.\\DISPLAY1", hdr: true },
        { name: "\\\\.\\DISPLAY2", hdr: false },
      ]),
    ).toEqual([
      { label: "HDR · Écran 1", value: "Activé" },
      { label: "HDR · Écran 2", value: "Désactivé dans Windows" },
    ]);
    expect(hdrRows([])).toEqual([{ label: "HDR", value: "Inconnu" }]);
    expect(displayName("odd", 2)).toBe("Écran 3");
  });

  it("says Inconnu rather than guess when nothing could be read", () => {
    expect(aboutRows(null, null).map((r) => r.value)).toEqual([
      "Inconnu",
      "Inconnu",
      "Inconnu",
      "Inconnu",
    ]);
  });
});
