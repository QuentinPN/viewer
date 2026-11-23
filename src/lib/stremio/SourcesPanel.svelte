<script lang="ts">
// « Autres sources »: the streams of the Stremio addons for an Emby film or
// episode, or a title outside Emby by its IMDb id, one section per addon in
// the account's order, shown as each addon answers. A stream is only an opaque id here: Go holds its URL and plays it.
import { onDestroy, onMount, tick } from "svelte";
import type { PlaybackStart } from "../emby/types";
import "./dialog.css";
import { stremioApi, toStremioError } from "./api";
import {
  addonAnswered,
  addonFailed,
  canRetry,
  outcomeNotes,
  type SourcesState,
  searchNote,
  shownAddons,
  startSources,
  streamLines,
  unsupportedNote,
} from "./sources";
import type { SourcesTarget, StreamDescriptor, StremioError } from "./types";

interface Props {
  /**
   * The Emby film or episode, or a title outside Emby. The stream played has
   * the `key` of the answer as `itemId`: the page reopens on it.
   */
  target: SourcesTarget;
  /** Shown over the list, e.g. after a stream that stopped answering. */
  notice?: string | null;
  onClose: () => void;
  /** The stream is loaded in the player: the page shows it. */
  onPlay: (start: PlaybackStart) => void;
}

let { target, notice = null, onClose, onPlay }: Props = $props();

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
const searching = $derived(sources ? searchNote(sources) : null);
const outcome = $derived(sources ? outcomeNotes(sources) : []);
const unsupported = $derived(unsupportedNote(sources?.unsupported ?? 0));

async function load() {
  const mine = ++run;
  sources = null;
  error = null;
  playError = null;
  try {
    const found = await stremioApi.sources(target);
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
      void tick().then(() => heading?.focus());
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
  class="st-dialog"
  aria-labelledby="sources-title"
  aria-busy={sources === null && error === null}
  onclose={onClose}
  onkeydown={(e) => e.stopPropagation()}
>
  <div class="st-head">
    <h2 id="sources-title" class="st-title" tabindex="-1" bind:this={heading}>Autres sources</h2>
    {#if note}<p class="notice" role="alert">{note}</p>{/if}
    {#if searching}<p class="muted" role="status" aria-live="polite">{searching}</p>{/if}
  </div>

  <div class="list">
    {#if error}
      <div class="state" role="alert">
        <p>{error.message}</p>
        {#if canRetry(error.kind)}
          <button type="button" class="st-btn" onclick={() => void load()}>Réessayer</button>
        {/if}
      </div>
    {:else if sources === null}
      <p class="muted" role="status">Recherche des addons…</p>
    {:else if sources.addons.length === 0}
      <p class="muted">Aucun addon Stremio ne propose ce type de titre.</p>
    {:else}
      {#each shownAddons(sources) as addon (addon.id)}
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
          {/if}
        </section>
      {/each}
      {#each outcome as line (line)}
        <p class="muted" role="status">{line}</p>
      {/each}
    {/if}
  </div>

  <div class="st-foot">
    {#if unsupported}<p class="muted small">{unsupported}</p>{/if}
    <button type="button" class="st-btn" onclick={() => dialog?.close()}>Fermer</button>
  </div>
</dialog>

<style>
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
  .small {
    font-size: 13px;
  }
  .st-foot .muted {
    margin-right: auto;
  }
</style>
