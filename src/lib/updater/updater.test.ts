import { get } from "svelte/store";
import { describe, expect, it, vi } from "vitest";
import type { UpdateInfo, UpdaterApi } from "./api";
import { formatNotes } from "./notes";
import { createUpdateStore } from "./store";

function fakeApi(pending: UpdateInfo | null = null) {
  let handler: ((info: UpdateInfo) => void) | null = null;
  const unlisten = vi.fn();
  const api: UpdaterApi = {
    pending: vi.fn(async () => pending),
    install: vi.fn(async () => {}),
    onReady: vi.fn(async (h) => {
      handler = h;
      return unlisten;
    }),
  };
  return { api, unlisten, fire: (info: UpdateInfo) => handler?.(info) };
}

const v12 = { version: "1.2.0", notes: "- Nouveau" };

describe("formatNotes", () => {
  it("turns markdown into plain lines", () => {
    expect(
      formatNotes(
        "## Nouveautés\n\n- **Mises à jour** automatiques\n* voir [le site](http://x)\n1. `code`\n---\nFin <b>ici</b>",
      ),
    ).toEqual([
      { kind: "heading", text: "Nouveautés" },
      { kind: "bullet", text: "Mises à jour automatiques" },
      { kind: "bullet", text: "voir le site" },
      { kind: "bullet", text: "code" },
      { kind: "text", text: "Fin ici" },
    ]);
  });

  it("keeps markup as text, never as HTML", () => {
    expect(formatNotes("<img src=x onerror=alert(1)>")).toEqual([]);
    expect(formatNotes("a < b")).toEqual([{ kind: "text", text: "a < b" }]);
    expect(formatNotes("")).toEqual([]);
  });
});

describe("createUpdateStore", () => {
  it("picks up an update that was ready before mount", async () => {
    const { api } = fakeApi(v12);
    const store = createUpdateStore(api);
    await store.start();
    expect(get(store).ready).toEqual(v12);
  });

  it("follows the ready event and stops listening on dispose", async () => {
    const { api, unlisten, fire } = fakeApi();
    const store = createUpdateStore(api);
    await store.start();
    expect(get(store).ready).toBeNull();
    fire(v12);
    expect(get(store).ready).toEqual(v12);
    store.dispose();
    expect(unlisten).toHaveBeenCalledOnce();
  });

  it("installs once and reports a failure", async () => {
    const { api } = fakeApi(v12);
    vi.mocked(api.install).mockRejectedValueOnce("échec");
    const store = createUpdateStore(api);
    await store.start();
    await Promise.all([store.install(), store.install()]);
    expect(api.install).toHaveBeenCalledOnce();
    expect(get(store).installing).toBe(false);
    expect(get(store).error).toMatch(/n'a pas pu démarrer/);
    expect(get(store).ready).toEqual(v12);
  });

  it("does nothing without an update", async () => {
    const { api } = fakeApi();
    const store = createUpdateStore(api);
    await store.start();
    await store.install();
    expect(api.install).not.toHaveBeenCalled();
  });
});
