<script lang="ts">
// « À demander · Request » under the Emby search results (Recherche C): runs
// on its own once Request is ready, never holds the Emby results back.
import { onDestroy, onMount } from "svelte";
import "./requests.css";
import { isSearchable } from "../search/query";
import { requestsApi } from "./api";
import MediaCard from "./MediaCard.svelte";
import { RequestSearch } from "./requestSearch.svelte";
import { toRequest } from "./searchMerge";
import type { MediaSummary, Viewer } from "./types";

interface Props {
  /** The cleaned query of the field. */
  query: string;
  onOpen: (media: MediaSummary, viewer: Viewer | null) => void;
}

let { query, onOpen }: Props = $props();

let ready = $state(false);
let viewer = $state<Viewer | null>(null);
const search = new RequestSearch();

const shown = $derived(search.results ? toRequest(search.results) : []);

onMount(() => {
  requestsApi.status().then(
    (s) => {
      viewer = s.viewer;
      ready = s.state === "ready";
    },
    // Not configured or unreachable: the Emby search stays as it was.
    () => {},
  );
});

$effect(() => {
  if (ready) search.set(query);
});

onDestroy(() => search.stop());
</script>

{#if ready && isSearchable(query)}
  <section class="ur-search" aria-labelledby="search-requests" aria-busy={search.loading}>
    <h2 id="search-requests">À demander · Request</h2>
    {#if search.error}
      <p class="ur-muted">Recherche Request indisponible</p>
    {:else if shown.length > 0}
      <ul class="ur-grid" class:stale={search.loading}>
        {#each shown as media (`${media.type}:${media.tmdbId}`)}
          <li><MediaCard {media} onOpen={(m) => onOpen(m, viewer)} /></li>
        {/each}
      </ul>
    {:else if search.loading}
      <p class="ur-muted">Recherche…</p>
    {:else if search.results}
      <p class="ur-muted">Rien d'autre à demander pour « {query} ».</p>
    {/if}
  </section>
{/if}

<style>
  .ur-search {
    margin-top: 40px;
  }
  h2 {
    margin: 0 0 var(--space-3);
    font-size: 20px;
    letter-spacing: -0.01em;
  }
  p {
    margin: 0;
  }
  .stale {
    opacity: 0.6;
  }
</style>
