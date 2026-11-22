<script lang="ts">
// The page of one playlist: its films and episodes in the playlist's
// order, as on the TV. « Tout lire » plays the first entry; every
// card plays its own entry at once. Read only: nothing is changed on the server.
import { onMount, tick } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  PlaybackStart,
  PlaylistCard,
  WatchItem,
} from "../emby/types";
import { watchTitle } from "./format";
import { firstEntry, playlistSummary } from "./playlist";
import WatchCard from "./WatchCard.svelte";

interface Props {
  playlist: PlaylistCard;
  onBack: () => void;
  onPlay: (start: PlaybackStart) => void;
  /** Reports the error upward (a lost server or a 401 changes the screen) and returns it. */
  onError: (e: unknown) => EmbyError;
}

let { playlist, onBack, onPlay, onError }: Props = $props();

let entries = $state<WatchItem[] | null>(null);
let loadError = $state<EmbyError | null>(null);
let starting = $state<string | null>(null);
let playError = $state<{ title: string; error: EmbyError } | null>(null);
let heading = $state<HTMLElement | null>(null);
let generation = 0;

async function load() {
  const gen = ++generation;
  loadError = null;
  try {
    const items = await embyApi.playlistItems(playlist.id);
    if (gen === generation) entries = items;
  } catch (e) {
    if (gen === generation) loadError = onError(e);
  }
}

function retry() {
  entries = null;
  void load();
}

async function play(item: WatchItem) {
  if (starting) return;
  starting = item.id;
  playError = null;
  try {
    onPlay(await embyApi.play(item.id, item.resume !== null));
  } catch (e) {
    playError = { title: watchTitle(item), error: onError(toEmbyError(e)) };
  } finally {
    starting = null;
  }
}

function playAll() {
  const first = entries ? firstEntry(entries) : null;
  if (first) void play(first);
}

onMount(() => {
  void load();
  // Keyboard users land on the page, not on the card they left.
  void tick().then(() => heading?.focus({ preventScroll: true }));
  const unlisten = embyApi.onPlaybackStopped(() => void load());
  return () => {
    generation++;
    void unlisten.then((stop) => stop());
  };
});
</script>

<section class="playlist" aria-labelledby="playlist-title" aria-busy={entries === null && !loadError}>
  <button type="button" class="back" onclick={onBack}>
    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
    Accueil
  </button>
  <header>
    <h1 id="playlist-title" tabindex="-1" bind:this={heading}>{playlist.name}</h1>
    {#if entries}
      <p class="summary">{playlistSummary(entries)}</p>
    {/if}
    {#if entries && entries.length > 0}
      <button
        type="button"
        class="primary"
        disabled={starting !== null}
        onclick={playAll}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11L13 8z" /></svg>
        {starting !== null && starting === entries[0]?.id ? "Ouverture…" : "Tout lire"}
      </button>
    {/if}
  </header>

  {#if playError}
    <div class="error" role="alert">
      <p class="error-title">Impossible de lire « {playError.title} »</p>
      <p>{playError.error.message}</p>
      {#if errorHint(playError.error)}<p class="hint">{errorHint(playError.error)}</p>{/if}
      <button type="button" class="secondary" onclick={() => (playError = null)}>Fermer</button>
    </div>
  {/if}

  {#if loadError}
    <div class="error" role="alert">
      <p class="error-title">Impossible de charger cette playlist</p>
      <p>{loadError.message}</p>
      {#if errorHint(loadError)}<p class="hint">{errorHint(loadError)}</p>{/if}
      <button type="button" class="secondary" onclick={retry}>Réessayer</button>
    </div>
  {:else if entries === null}
    <p class="muted">Chargement…</p>
  {:else if entries.length === 0}
    <p class="muted">Aucun film ni épisode dans cette playlist.</p>
  {:else}
    <ol class="grid" aria-label="Titres de {playlist.name}">
      {#each entries as item (item.id)}
        <li>
          <WatchCard
            {item}
            busy={starting === item.id}
            disabled={starting !== null}
            onPlay={(i) => void play(i)}
          />
        </li>
      {/each}
    </ol>
  {/if}
</section>

<style>
  .playlist {
    display: block;
  }
  header {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
    margin: var(--space-3) 0 var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 32px;
    font-weight: 700;
  }
  h1:focus {
    outline: none;
  }
  h1:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 4px;
    border-radius: var(--radius-sm);
  }
  .summary,
  .muted {
    margin: 0;
    color: var(--color-text-secondary);
  }
  button {
    font: inherit;
    cursor: pointer;
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button svg {
    width: 16px;
    height: 16px;
  }
  .back {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    background: transparent;
    border: 0;
    border-radius: var(--radius-pill);
    padding: var(--space-1) var(--space-3) var(--space-1) var(--space-2);
    color: var(--color-text-secondary);
  }
  .back:hover {
    background: rgb(255 255 255 / 0.1);
    color: var(--color-text);
  }
  .back svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .primary {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: var(--space-2);
    border: 0;
    border-radius: var(--radius-pill);
    padding: var(--space-2) var(--space-6);
    background: var(--color-text);
    color: var(--color-bg);
    font-weight: 600;
  }
  .primary svg {
    fill: currentColor;
  }
  .primary:disabled {
    opacity: 0.6;
    cursor: default;
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: var(--space-6) var(--space-4);
  }
  .error {
    max-width: 640px;
    margin-bottom: var(--space-6);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background: var(--color-surface);
    border-left: 3px solid var(--color-danger);
    color: var(--color-text-secondary);
  }
  .error p {
    margin: var(--space-1) 0;
  }
  .error-title {
    color: var(--color-text);
    font-weight: 600;
  }
  .hint {
    font-size: 13px;
    color: var(--color-text-muted);
  }
  .secondary {
    margin-top: var(--space-2);
    background: var(--fill-glass);
    color: var(--color-text);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font-weight: 600;
  }
  .secondary:hover {
    background: var(--fill-glass-hover);
  }
</style>
