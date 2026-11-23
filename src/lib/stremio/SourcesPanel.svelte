<script lang="ts">
// « Autres sources »: the streams of the Stremio addons for an Emby film or
// episode, one section per addon in the account's order, shown as each addon
// answers. A stream is only an opaque id here: Go holds its URL and plays it.
import { onDestroy, onMount, tick } from "svelte";
import type { PlaybackStart } from "../emby/types";
import { stremioApi, toStremioError } from "./api";
import {
  addonAnswered,
  addonFailed,
  addonNote,
  type SourcesState,
  startSources,
  streamLines,
  unsupportedNote,
} from "./sources";
import type { StreamDescriptor, StremioError } from "./types";

interface Props {
  /** The Emby film or episode. */
  itemId: string;
  /** Shown over the list, e.g. after a stream that stopped answering. */
  notice?: string | null;
  onClose: () => void;
  /** The stream is loaded in the player: the page shows it. */
  onPlay: (start: PlaybackStart) => void;
}

let { itemId, notice = null, onClose, onPlay }: Props = $props();

/** Errors where a new query cannot help: the fix is in Réglages or the title. */
const FINAL = new Set(["signedOut", "reauthRequired", "noImdb"]);

let dialog = $state<HTMLDialogElement | null>(null);
let heading = $state<HTMLHeadingElement | null>(null);
let sources = $state<SourcesState | null>(null);
let error = $state<StremioError | null>(null);
/** The last failed play, over the list. */
let playError = $state<string | null>(null);
/** Id of the stream being opened: the other buttons wait. */
let starting = $state<string | null>(null);
/** Closed: late answers are dropped. */
let closed = false;
/** Bumped by each query: answers of an older one are dropped too. */
let run = 0;

const note = $derived(playError ?? notice);
const unsupported = $derived(unsupportedNote(sources?.unsupported ?? 0));

async function load() {
  const mine = ++run;
  sources = null;
  error = null;
  playError = null;
  try {
    const found = await stremioApi.sources(itemId);
    if (closed || mine !== run) return;
    sources = startSources(found);
    for (const addon of sources.addons) void ask(found.query, addon.id);
  } catch (e) {
    if (!closed && mine === run) error = toStremioError(e);
  }
}

async function ask(query: string, addonId: string) {
  try {
    const answer = await stremioApi.addonStreams(query, addonId);
    if (!closed && sources)
      sources = addonAnswered(sources, query, addonId, answer);
  } catch (e) {
    if (closed || !sources || sources.query !== query) return;
    const failure = toStremioError(e);
    // The whole list is gone on the Go side: say so once, not per addon.
    if (failure.kind === "expired") {
      sources = null;
      error = failure;
    } else sources = addonFailed(sources, query, addonId, failure.message);
  }
}

async function play(stream: StreamDescriptor) {
  if (starting) return;
  starting = stream.id;
  playError = null;
  try {
    const start = await stremioApi.play(stream.id);
    // Loaded already: closing meanwhile must not leave it playing unseen.
    onPlay(start);
  } catch (e) {
    if (closed) return;
    const failure = toStremioError(e);
    if (failure.kind === "expired") {
      run++;
      sources = null;
      error = failure;
      // The stream button that had the focus is gone with the list.
      void tick().then(() => heading?.focus());
    } else playError = failure.message;
  } finally {
    starting = null;
  }
}

onMount(() => {
  dialog?.showModal();
  void tick().then(() => heading?.focus());
  void load();
});

onDestroy(() => {
  closed = true;
});
</script>

<dialog
  bind:this={dialog}
  class="dialog"
  aria-labelledby="sources-title"
  aria-busy={sources === null && error === null}
  onclose={onClose}
  onkeydown={(e) => e.stopPropagation()}
>
  <div class="head">
    <h2 id="sources-title" tabindex="-1" bind:this={heading}>Autres sources</h2>
    {#if note}<p class="notice" role="alert">{note}</p>{/if}
  </div>

  <div class="list">
    {#if error}
      <div class="state" role="alert">
        <p>{error.message}</p>
        {#if !FINAL.has(error.kind)}
          <button type="button" class="btn" onclick={() => void load()}>Réessayer</button>
        {/if}
      </div>
    {:else if sources === null}
      <p class="muted" role="status">Recherche des addons…</p>
    {:else if sources.addons.length === 0}
      <p class="muted">Aucun addon Stremio ne propose ce type de titre.</p>
    {:else}
      {#each sources.addons as addon (addon.id)}
        {@const addonState = addon.state}
        <section class="addon" aria-labelledby="addon-{addon.id}">
          <h3 id="addon-{addon.id}">{addon.name}</h3>
          {#if addonState.kind === "ok"}
            <ul>
              {#each addonState.streams as stream (stream.id)}
                {@const shown = streamLines(stream)}
                <li>
                  <button
                    type="button"
                    class="stream"
                    aria-disabled={starting !== null}
                    aria-busy={starting === stream.id}
                    onclick={() => void play(stream)}
                  >
                    <span class="text">
                      <span class="title">{shown.title}</span>
                      {#each shown.details as line, i (i)}
                        <span class="detail">{line}</span>
                      {/each}
                    </span>
                    {#if starting === stream.id}
                      <span class="size">Ouverture…</span>
                    {:else if shown.size}
                      <span class="size">{shown.size}</span>
                    {/if}
                  </button>
                </li>
              {/each}
            </ul>
          {:else}
            <p class="muted" role={addonState.kind === "loading" ? undefined : "status"}>
              {addonNote(addonState)}
            </p>
          {/if}
        </section>
      {/each}
    {/if}
  </div>

  <div class="foot">
    {#if unsupported}<p class="muted small">{unsupported}</p>{/if}
    <button type="button" class="btn" onclick={() => dialog?.close()}>Fermer</button>
  </div>
</dialog>

<style>
  .dialog {
    width: min(640px, calc(100vw - 32px));
    max-height: calc(100vh - 64px);
    padding: 0;
    border: 1px solid var(--separator);
    border-radius: var(--radius-lg);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    font: inherit;
  }
  .dialog[open] {
    display: flex;
    flex-direction: column;
  }
  .dialog::backdrop {
    background: rgb(0 0 0 / 0.55);
    backdrop-filter: blur(6px);
  }
  p {
    margin: 0;
  }
  .head {
    display: grid;
    gap: var(--space-3);
    padding: var(--space-6) var(--space-6) var(--space-4);
  }
  h2 {
    margin: 0;
    font-family: var(--font-display);
    font-size: 22px;
  }
  h2:focus {
    outline: none;
  }
  .notice {
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-sm);
    background: rgb(255 255 255 / 0.06);
    color: var(--color-warning);
    font-size: 14px;
  }
  .list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0 var(--space-6);
    display: grid;
    align-content: start;
    gap: var(--space-4);
  }
  .addon {
    display: grid;
    gap: var(--space-2);
  }
  h3 {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-muted);
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: 2px;
  }
  .stream {
    width: 100%;
    display: grid;
    grid-template-columns: 1fr auto;
    align-items: start;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 0;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    text-align: left;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .stream:hover:not([aria-disabled="true"]) {
    background: rgb(255 255 255 / 0.06);
  }
  .stream[aria-disabled="true"] {
    cursor: default;
  }
  .text {
    display: grid;
    gap: 2px;
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .title {
    font-weight: 600;
  }
  .detail {
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .size {
    font-size: 13px;
    color: var(--color-text-secondary);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  .state {
    display: grid;
    justify-items: start;
    gap: var(--space-3);
  }
  .muted {
    color: var(--color-text-muted);
  }
  .small {
    font-size: 13px;
  }
  .foot {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: var(--space-4);
    padding: var(--space-4) var(--space-6) var(--space-6);
  }
  .foot .muted {
    margin-right: auto;
  }
  .btn {
    height: 38px;
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .btn:hover {
    background: var(--fill-glass-hover);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
</style>
