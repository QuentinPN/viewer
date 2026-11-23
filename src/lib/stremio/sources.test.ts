import { describe, expect, it } from "vitest";
import {
  addonAnswered,
  addonFailed,
  isStremio,
  offersSources,
  outcomeNotes,
  searchNote,
  shownAddons,
  startSources,
  streamLines,
  unsupportedNote,
} from "./sources";

const sources = {
  query: "q1",
  addons: [
    { id: "a1", name: "Comet" },
    { id: "a2", name: "Lumio" },
  ],
};

const stream = {
  id: "stream_1",
  name: "Comet\n4K",
  description: "Film.2024.2160p.mkv\n👤 12  ⚙️ RD\n",
  size: 34_037_000_000,
};

describe("startSources", () => {
  it("lists every addon as searching, in order", () => {
    const s = startSources(sources);
    expect(s.query).toBe("q1");
    expect(s.addons.map((a) => [a.id, a.name, a.state.kind])).toEqual([
      ["a1", "Comet", "loading"],
      ["a2", "Lumio", "loading"],
    ]);
    expect(s.unsupported).toBe(0);
  });

  it("takes a null list from Go as no addon", () => {
    expect(startSources({ query: "q1", addons: null }).addons).toEqual([]);
  });
});

describe("addonAnswered", () => {
  it("shows the streams of the addon that answered, the others still searching", () => {
    const s = addonAnswered(startSources(sources), "q1", "a2", {
      streams: [stream],
      unsupported: 3,
    });
    expect(s.addons[0]?.state).toEqual({ kind: "loading" });
    expect(s.addons[1]?.state).toEqual({ kind: "ok", streams: [stream] });
    expect(s.unsupported).toBe(3);
  });

  it("adds up the unsupported streams of every addon", () => {
    let s = startSources(sources);
    s = addonAnswered(s, "q1", "a1", { streams: [], unsupported: 2 });
    s = addonAnswered(s, "q1", "a2", { streams: null, unsupported: 1 });
    expect(s.unsupported).toBe(3);
  });

  it("marks an addon without stream as empty", () => {
    const s = addonAnswered(startSources(sources), "q1", "a1", {
      streams: null,
      unsupported: 0,
    });
    expect(s.addons[0]?.state).toEqual({ kind: "empty" });
  });

  it("drops an answer for another query", () => {
    const before = startSources(sources);
    const after = addonAnswered(before, "q0", "a1", {
      streams: [stream],
      unsupported: 5,
    });
    expect(after).toBe(before);
  });

  it("drops an answer for an unknown addon", () => {
    const before = startSources(sources);
    expect(
      addonAnswered(before, "q1", "zz", { streams: [], unsupported: 1 }),
    ).toBe(before);
  });
});

describe("addonFailed", () => {
  it("keeps Go's message for the addon", () => {
    const s = addonFailed(
      startSources(sources),
      "q1",
      "a1",
      "Comet : indisponible.",
    );
    expect(s.addons[0]?.state).toEqual({
      kind: "error",
      message: "Comet : indisponible.",
    });
    expect(s.addons[1]?.state).toEqual({ kind: "loading" });
  });

  it("drops a failure for another query", () => {
    const before = startSources(sources);
    expect(addonFailed(before, "q0", "a1", "x")).toBe(before);
  });
});

describe("shownAddons", () => {
  it("keeps only the addons with streams, in order", () => {
    let s = startSources(sources);
    expect(shownAddons(s)).toEqual([]);
    s = addonAnswered(s, "q1", "a2", { streams: [stream], unsupported: 0 });
    s = addonFailed(s, "q1", "a1", "Comet : indisponible.");
    expect(shownAddons(s).map((a) => a.id)).toEqual(["a2"]);
  });
});

describe("searchNote", () => {
  it("counts the addons still searching", () => {
    let s = startSources(sources);
    expect(searchNote(s)).toBe("Recherche dans 2 sources…");
    s = addonFailed(s, "q1", "a1", "x");
    expect(searchNote(s)).toBe("Recherche dans 1 source…");
    s = addonAnswered(s, "q1", "a2", { streams: [], unsupported: 0 });
    expect(searchNote(s)).toBeNull();
  });
});

describe("outcomeNotes", () => {
  it("says nothing while an addon is searching or a stream is listed", () => {
    let s = startSources(sources);
    expect(outcomeNotes(s)).toEqual([]);
    s = addonAnswered(s, "q1", "a1", { streams: [stream], unsupported: 0 });
    s = addonAnswered(s, "q1", "a2", { streams: [], unsupported: 0 });
    expect(outcomeNotes(s)).toEqual([]);
  });

  it("says no source was found once every addon is done", () => {
    let s = startSources(sources);
    s = addonAnswered(s, "q1", "a1", { streams: [], unsupported: 0 });
    s = addonAnswered(s, "q1", "a2", { streams: [], unsupported: 0 });
    expect(outcomeNotes(s)).toEqual(["Aucune source trouvée."]);
  });

  it("adds how many addons did not answer", () => {
    let s = startSources(sources);
    s = addonAnswered(s, "q1", "a1", { streams: [], unsupported: 0 });
    s = addonFailed(s, "q1", "a2", "x");
    expect(outcomeNotes(s)).toEqual([
      "Aucune source trouvée.",
      "1 source n'a pas répondu.",
    ]);
    s = addonFailed(s, "q1", "a1", "y");
    expect(outcomeNotes(s)[1]).toBe("2 sources n'ont pas répondu.");
  });

  it("says nothing without addon", () => {
    expect(outcomeNotes(startSources({ query: "q1", addons: [] }))).toEqual([]);
  });
});

describe("streamLines", () => {
  it("joins the name on one line and keeps the description lines", () => {
    expect(streamLines(stream)).toEqual({
      title: "Comet · 4K",
      details: ["Film.2024.2160p.mkv", "👤 12  ⚙️ RD"],
      size: "31,7 Go",
    });
  });

  it("has no size when the addon gave none", () => {
    expect(
      streamLines({ id: "s", name: "Lumio", description: "", size: null }).size,
    ).toBeNull();
  });

  it("trims blank lines and handles Windows line ends", () => {
    expect(
      streamLines({
        id: "s",
        name: " RD+ \r\n\r\n1080p ",
        description: "\r\nA\r\n  \r\nB",
        size: 0,
      }),
    ).toEqual({ title: "RD+ · 1080p", details: ["A", "B"], size: null });
  });
});

describe("unsupportedNote", () => {
  it("counts the streams that cannot be played", () => {
    expect(unsupportedNote(0)).toBeNull();
    expect(unsupportedNote(1)).toBe(
      "1 flux non pris en charge (torrent sans lien)",
    );
    expect(unsupportedNote(12)).toBe(
      "12 flux non pris en charge (torrent sans lien)",
    );
  });
});

describe("offersSources", () => {
  it("offers other sources on a film or an episode once Stremio is linked", () => {
    expect(offersSources("signed_in", "movie")).toBe(true);
    expect(offersSources("signed_in", "episode")).toBe(true);
    expect(offersSources("reauth_required", "movie")).toBe(true);
  });

  it("offers nothing on a series, signed out or before the status is known", () => {
    expect(offersSources("signed_in", "series")).toBe(false);
    expect(offersSources("signed_out", "movie")).toBe(false);
    expect(offersSources("unreachable", "movie")).toBe(false);
    expect(offersSources(null, "movie")).toBe(false);
  });
});

describe("isStremio", () => {
  it("tells a Stremio playback, a missing origin being Emby", () => {
    expect(isStremio({ origin: "stremio" })).toBe(true);
    expect(isStremio({ origin: "emby" })).toBe(false);
    expect(isStremio({})).toBe(false);
  });
});
