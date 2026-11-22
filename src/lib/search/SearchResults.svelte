<script lang="ts">
// Search results in place of the current view: films then series, as
// poster grids, then episodes as 16:9 cards; each opens its page.
import { errorHint } from "../emby/api";
import type {
  EmbyError,
  MediaItem,
  SearchResults,
  WatchItem,
} from "../emby/types";
import WatchCard from "../home/WatchCard.svelte";
import PosterCard from "../library/PosterCard.svelte";
import SearchSection from "../requests/SearchSection.svelte";
import type { MediaSummary, Viewer } from "../requests/types";
import { isSearchable, resultsLabel, SEARCH_SHOWN } from "./query";

interface Props {
  /** The cleaned query of the field. */
  query: string;
  /** Last answer, possibly for an earlier query while a new one loads. */
  results: SearchResults | null;
  loading: boolean;
  error: EmbyError | null;
  onRetry: () => void;
  onOpen: (item: MediaItem) => void;
  onOpenEpisode: (item: WatchItem) => void;
  /** A title of « À demander · Request » opens its page. */
  onOpenRequest: (media: MediaSummary, viewer: Viewer | null) => void;
}

let {
  query,
  results,
  loading,
  error,
  onRetry,
  onOpen,
  onOpenEpisode,
  onOpenRequest,
}: Props = $props();

const tooShort = $derived(!isSearchable(query));
const empty = $derived(
  results !== null &&
    results.movies.length === 0 &&
    results.series.length === 0 &&
    results.episodes.length === 0,
);
const status = $derived.by(() => {
  if (tooShort) return "Saisis au moins 2 caractères.";
  if (error) return "La recherche a échoué.";
  if (loading || !results) return "Recherche…";
  return resultsLabel(results);
});
</script>

<section class="search" aria-labelledby="search-title" aria-busy={loading}>
  <header class="heading">
    <h1 id="search-title">Recherche</h1>
    <p class="status" role="status">{status}</p>
  </header>

  {#if tooShort}
    <!-- The status says it all. -->
  {:else if error}
    <div class="state" role="alert">
      <p class="state-title">Impossible de lancer la recherche</p>
      <p>{error.message}</p>
      {#if errorHint(error)}<p class="hint">{errorHint(error)}</p>{/if}
      <button type="button" onclick={onRetry}>Réessayer</button>
    </div>
  {:else if !results}
    <ul class="grid skeleton-grid" aria-hidden="true">
      {#each { length: 12 } as _, i (i)}
        <li class="skeleton">
          <div class="sk-poster"></div>
          <div class="sk-line"></div>
        </li>
      {/each}
    </ul>
  {:else if empty}
    <div class="state" class:stale={loading}>
      <p class="state-title">Aucun film, série ou épisode pour « {results.query} »</p>
      <p class="hint">
        La recherche porte sur le début des mots du titre : « heure » trouve
        « 24 Heures chrono », « eures » ne le trouve pas.
      </p>
    </div>
  {:else}
    <div class="groups" class:stale={loading}>
      {#if results.movies.length > 0}
        <section aria-labelledby="search-movies">
          <h2 id="search-movies">Films</h2>
          <ul class="grid" aria-labelledby="search-movies">
            {#each results.movies as item (item.id)}
              <li><PosterCard {item} {onOpen} /></li>
            {/each}
          </ul>
          {#if results.moreMovies}
            <p class="hint more">
              Seuls les {SEARCH_SHOWN} premiers films sont affichés : précise la
              recherche.
            </p>
          {/if}
        </section>
      {/if}
      {#if results.series.length > 0}
        <section aria-labelledby="search-series">
          <h2 id="search-series">Séries</h2>
          <ul class="grid" aria-labelledby="search-series">
            {#each results.series as item (item.id)}
              <li><PosterCard {item} {onOpen} /></li>
            {/each}
          </ul>
          {#if results.moreSeries}
            <p class="hint more">
              Seules les {SEARCH_SHOWN} premières séries sont affichées : précise
              la recherche.
            </p>
          {/if}
        </section>
      {/if}
      {#if results.episodes.length > 0}
        <section aria-labelledby="search-episodes">
          <h2 id="search-episodes">Épisodes</h2>
          <ul class="grid wide" aria-labelledby="search-episodes">
            {#each results.episodes as item (item.id)}
              <li><WatchCard {item} onOpen={onOpenEpisode} /></li>
            {/each}
          </ul>
          {#if results.moreEpisodes}
            <p class="hint more">
              D'autres épisodes peuvent correspondre : précise la recherche.
            </p>
          {/if}
        </section>
      {/if}
    </div>
  {/if}
  <SearchSection {query} onOpen={onOpenRequest} />
</section>

<style>
  .heading {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  .status {
    margin: 0;
    color: var(--color-text-muted);
  }
  .status:first-letter {
    text-transform: uppercase;
  }
  h2 {
    margin: 0 0 var(--space-3);
    font-size: 20px;
    letter-spacing: -0.01em;
  }
  .groups {
    display: grid;
    gap: 32px;
    transition: opacity var(--motion-fast);
  }
  .stale {
    opacity: 0.6;
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: var(--space-6) var(--space-4);
  }
  .grid.wide {
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  }
  @media (min-width: 1600px) {
    .grid {
      grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
    }
    .grid.wide {
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    }
  }
  .skeleton .sk-poster {
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    background: var(--color-surface);
    animation: pulse 1.4s ease-in-out infinite;
  }
  .skeleton .sk-line {
    margin-top: var(--space-2);
    height: 12px;
    width: 70%;
    border-radius: 6px;
    background: var(--color-surface);
    animation: pulse 1.4s ease-in-out infinite;
  }
  @keyframes pulse {
    50% {
      opacity: 0.55;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .skeleton .sk-poster,
    .skeleton .sk-line {
      animation: none;
    }
    .groups {
      transition: none;
    }
  }
  .state {
    max-width: 520px;
    margin: 10vh auto 0;
    text-align: center;
    color: var(--color-text-secondary);
  }
  .state p {
    margin: var(--space-1) 0;
  }
  .state-title {
    color: var(--color-text);
    font-size: 18px;
    font-weight: 600;
  }
  .hint {
    font-size: 13px;
    color: var(--color-text-muted);
  }
  .more {
    margin: var(--space-4) 0 0;
  }
  .state button {
    margin-top: var(--space-4);
    background: var(--color-accent);
    color: #04121b;
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .state button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
</style>
