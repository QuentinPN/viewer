import { describe, expect, it } from "vitest";
import type { MediaInfo } from "../emby/types";
import {
  loadVersion,
  saveVersion,
  versionChoices,
  versionKey,
  versionLabel,
} from "./versions";

function media(over: Partial<MediaInfo>): MediaInfo {
  return {
    id: "mediasource_30500",
    name: null,
    container: "mkv",
    sizeBytes: null,
    bitrate: null,
    video: null,
    audio: [],
    subtitles: [],
    ...over,
  };
}

const hd = media({
  name: "1080p",
  sizeBytes: 4_000_000_000,
  video: {
    codec: "h264",
    width: 1920,
    height: 1080,
    bitDepth: 8,
    range: "SDR",
  },
});
const uhd = media({
  id: "30501",
  name: "2160p",
  sizeBytes: 30_000_000_000,
  video: {
    codec: "hevc",
    width: 3840,
    height: 2160,
    bitDepth: 10,
    range: "HDR 10",
  },
});

class MemoryStorage {
  data = new Map<string, string>();
  getItem(k: string) {
    return this.data.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.data.set(k, v);
  }
  removeItem(k: string) {
    this.data.delete(k);
  }
}

describe("version labels", () => {
  it("names the server's version, then resolution, codec, HDR and size", () => {
    expect(versionLabel(hd, 0)).toBe("1080p — 1080p · H.264 · 3,7 Go");
    expect(versionLabel(uhd, 1)).toBe(
      "2160p — 4K · HEVC 10 bits · HDR 10 · 27,9 Go",
    );
  });

  it("falls back to the bitrate, a number and the name alone", () => {
    expect(versionLabel(media({ name: " ", bitrate: 60_000_000 }), 2)).toBe(
      "Version 3 — 60 Mb/s",
    );
    expect(versionLabel(media({ name: "Director's cut" }), 0)).toBe(
      "Director's cut",
    );
  });
});

describe("version choices", () => {
  it("offers a choice only with two versions or more that have an id", () => {
    expect(versionChoices([hd])).toEqual([]);
    expect(versionChoices([hd, media({ id: null })])).toEqual([]);
    expect(
      versionChoices([hd, media({ id: null }), uhd]).map((c) => c.id),
    ).toEqual(["mediasource_30500", "30501"]);
  });

  it("remembers a choice per server, user and item, the default unstored", () => {
    const storage = new MemoryStorage();
    const choices = versionChoices([hd, uhd]);
    const a = versionKey("srv:u1", "30500");
    const b = versionKey("srv:u2", "30500");
    expect(loadVersion(storage, a, choices)).toBe("mediasource_30500");
    saveVersion(storage, a, choices, "30501");
    expect(loadVersion(storage, a, choices)).toBe("30501");
    expect(loadVersion(storage, b, choices)).toBe("mediasource_30500");
    saveVersion(storage, a, choices, "mediasource_30500");
    expect(storage.data.size).toBe(0);
  });

  it("forgets a version the server no longer offers and survives storage errors", () => {
    const storage = new MemoryStorage();
    const key = versionKey("srv:u1", "30500");
    storage.setItem(key, "30502");
    expect(loadVersion(storage, key, versionChoices([hd, uhd]))).toBe(
      "mediasource_30500",
    );
    const broken = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("quota");
      },
      removeItem: () => {},
    };
    expect(loadVersion(broken, key, versionChoices([hd, uhd]))).toBe(
      "mediasource_30500",
    );
    expect(() =>
      saveVersion(broken, key, versionChoices([hd, uhd]), "30501"),
    ).not.toThrow();
    expect(loadVersion(null, key, [])).toBeNull();
  });
});
