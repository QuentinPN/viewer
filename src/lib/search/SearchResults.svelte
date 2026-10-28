<script lang="ts">
// Search results in place of the current view (ADR 0011): films then series,
// as poster grids opening the detail page.
import { errorHint } from "../emby/api";
import type { EmbyError, MediaItem, SearchResults } from "../emby/types";
import PosterCard from "../library/PosterCard.svelte";
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
}

let { query, results, loading, error, onRetry, onOpen }: Props = $props();

const tooShort = $derived(!isSearchable(query));
const empty = $derived(
  results !== null &&
    results.movies.length === 0 &&
    results.series.length === 0,
);
const status = $derived.by(() => {
  if (tooShort) return "Saisissez au moins 2 caractères.";
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
      <p class="state-title">Aucun film ni aucune série pour « {results.query} »</p>
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
              Seuls les {SEARCH_SHOWN} premiers films sont affichés : précisez la
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
              Seules les {SEARCH_SHOWN} premières séries sont affichées : précisez
              la recherche.
            </p>
          {/if}
        </section>
      {/if}
    </div>
  {/if}
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
  @media (min-width: 1600px) {
    .grid {
      grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
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
