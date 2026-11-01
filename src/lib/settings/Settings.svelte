<script lang="ts">
import { onMount } from "svelte";
import { embyApi, toEmbyError } from "../emby/api";
import type { ArtworkCacheStats, EmbyError } from "../emby/types";
import {
  cacheUsage,
  clearedLabel,
  imageCount,
  sessionReads,
  usageRatio,
} from "./format";

// `undefined` while loading, `null` when the app has no artwork cache.
let stats = $state<ArtworkCacheStats | null | undefined>(undefined);
let loadError = $state<EmbyError | null>(null);
let clearing = $state(false);
let clearError = $state<EmbyError | null>(null);
let announce = $state("");

async function load() {
  loadError = null;
  try {
    stats = await embyApi.artworkStats();
  } catch (e) {
    loadError = toEmbyError(e);
  }
}

async function clear() {
  if (clearing) return;
  clearing = true;
  clearError = null;
  announce = "";
  try {
    const cleared = await embyApi.clearArtwork();
    announce = cleared
      ? clearedLabel(cleared)
      : "Le cache des images n'est pas disponible sur ce poste.";
    await load();
  } catch (e) {
    clearError = toEmbyError(e);
  } finally {
    clearing = false;
  }
}

onMount(() => {
  void load();
});
</script>

<header class="heading">
  <h1>Réglages</h1>
</header>

<section class="panel" aria-labelledby="settings-artwork">
  <h2 id="settings-artwork">Cache des images</h2>
  <p class="lead">
    Les affiches et les fonds déjà vus sont gardés sur ce poste pour s'afficher
    plus vite. Les vider ne change rien sur le serveur : ils seront téléchargés de
    nouveau à leur prochain affichage.
  </p>

  {#if loadError}
    <div class="problem" role="alert">
      <p>Impossible de lire la taille du cache : {loadError.message}</p>
      <button type="button" class="secondary" onclick={load}>Réessayer</button>
    </div>
  {:else if stats === undefined}
    <p class="muted" aria-busy="true">Lecture du cache…</p>
  {:else if stats === null}
    <p class="muted">Le cache des images n'est pas disponible sur ce poste.</p>
  {:else}
    <div class="usage">
      <p class="size">
        <strong>{cacheUsage(stats)}</strong>
        <span class="muted">· {imageCount(stats.entries)}</span>
      </p>
      <div class="bar" aria-hidden="true">
        <span style:width="{usageRatio(stats) * 100}%"></span>
      </div>
      <p class="muted reads">{sessionReads(stats)}</p>
    </div>
  {/if}

  {#if stats !== null}
    <button
      type="button"
      class="primary"
      onclick={clear}
      aria-disabled={clearing ? "true" : undefined}
      disabled={stats === undefined || loadError !== null}
    >
      {clearing ? "Vidage…" : "Vider le cache des images"}
    </button>
  {/if}
  <p class="result" role="status">{announce}</p>
  {#if clearError}
    <p class="problem-text" role="alert">
      Le cache n'a pas pu être vidé : {clearError.message}
    </p>
  {/if}
</section>

<style>
  .heading {
    margin-bottom: var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  .panel {
    max-width: 640px;
    background: var(--color-surface);
    border-radius: var(--radius-md);
    padding: var(--space-6);
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  p {
    margin: 0;
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  .muted {
    color: var(--color-text-muted);
  }
  .usage {
    display: grid;
    gap: var(--space-2);
    width: 100%;
    margin: var(--space-2) 0;
  }
  .size strong {
    font-size: 20px;
    font-weight: 600;
  }
  .bar {
    height: 6px;
    width: 100%;
    max-width: 420px;
    background: var(--color-elevated);
    border-radius: 3px;
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: var(--color-accent);
    transition: width var(--motion-base);
  }
  .reads {
    font-size: 13px;
  }
  button {
    font: inherit;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
  }
  .primary {
    background: var(--color-accent);
    color: #04121b;
    border: 0;
    font-weight: 600;
  }
  .secondary {
    background: transparent;
    color: var(--color-text);
    border: 1px solid var(--color-elevated);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  /* Not `disabled` while emptying: the keyboard focus stays on the button. */
  button:disabled,
  button[aria-disabled="true"] {
    opacity: 0.55;
    cursor: default;
  }
  .result {
    color: var(--color-success);
  }
  .problem {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
  }
  .problem p,
  .problem-text {
    color: var(--color-danger);
  }
</style>
