<script lang="ts">
// Search of the Demander tab (liste B): one row per title with its status and
// one action: « Lire » when it is on Emby, « Demander » when it may be asked,
// « Voir » otherwise; the last two open its page.
import { onDestroy } from "svelte";
import type { MediaItem } from "../emby/types";
import "./requests.css";
import { mediaStatusText, statusTone, typeLabel } from "./labels";
import { RequestSearch } from "./requestSearch.svelte";
import { embyItem, openStatus } from "./seasons";
import { currentStatus } from "./statusOverrides.svelte";
import Thumb from "./Thumb.svelte";
import type { MediaSummary, Viewer } from "./types";

interface Props {
  query: string;
  viewer: Viewer | null;
  onOpen: (media: MediaSummary) => void;
  onPlay: (item: MediaItem) => void;
}

let { query, viewer, onOpen, onPlay }: Props = $props();

const search = new RequestSearch();

$effect(() => search.set(query));
onDestroy(() => search.stop());

/** A first look from the summary; the page decides with the seasons. */
function mayAsk(m: MediaSummary): boolean {
  const caps = viewer?.capabilities;
  if (!caps || m.blocked) return false;
  if (!(m.type === "movie" ? caps.canRequestMovies : caps.canRequestTv))
    return false;
  return openStatus(currentStatus(m).status);
}

function meta(m: MediaSummary): string {
  return [typeLabel(m.type), m.year ? String(m.year) : null]
    .filter((p): p is string => p !== null)
    .join(" · ");
}
</script>

<section aria-labelledby="ur-search-title" aria-busy={search.loading}>
  <h2 id="ur-search-title" class="ur-sr-only">Résultats Request</h2>
  {#if search.error}
    <div class="state" role="alert">
      <p>{search.error.message}</p>
      <button type="button" class="ur-btn" onclick={() => search.set(query)}>
        Réessayer
      </button>
    </div>
  {:else if search.results === null}
    <p class="ur-muted">{search.loading ? "Recherche…" : "Saisis au moins 2 caractères."}</p>
  {:else if search.results.length === 0}
    <p class="ur-muted">Aucun titre pour « {query} ».</p>
  {:else}
    <ul class:stale={search.loading}>
      {#each search.results as m (`${m.type}:${m.tmdbId}`)}
        {@const st = currentStatus(m)}
        {@const playable = embyItem(m)}
        <li class="row">
          <Thumb image={m.poster} />
          <span class="text">
            <span class="title">{m.title}</span>
            <span class="meta ur-muted">{meta(m)}</span>
          </span>
          {#if st.status}
            <span class="ur-pill ur-tone-{statusTone(st.status)}">
              {mediaStatusText(st.status, st.progress)}
            </span>
          {/if}
          {#if playable}
            {@const item = playable}
            <button type="button" class="ur-btn primary" onclick={() => onPlay(item)}>
              Lire
            </button>
          {:else if mayAsk(m)}
            <button type="button" class="ur-btn primary" onclick={() => onOpen(m)}>
              Demander
            </button>
          {:else}
            <button type="button" class="ur-btn" onclick={() => onOpen(m)}>Voir</button>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  p {
    margin: 0;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-2);
    transition: opacity var(--motion-fast);
  }
  .stale {
    opacity: 0.6;
  }
  .row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background: var(--color-surface);
  }
  .text {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .title {
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta {
    font-size: 13px;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
</style>
