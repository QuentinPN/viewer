<script lang="ts">
// Parcourir (A): the films or series of `GET /api/discover/{type}` with the
// library's toolbar (sort, genre, years, minimum rating), a poster grid that loads
// the next page before the end, skeletons and an empty state.
import { onMount, tick, untrack } from "svelte";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import {
  browseArgs,
  canReset,
  DEFAULT_FILTERS,
  decadeOf,
  decades,
  genreChoice,
  mergePage,
  RATINGS,
  SORTS,
  withDecade,
  withGenre,
  yearsLabel,
} from "./browse";
import MediaCard from "./MediaCard.svelte";
import type {
  Genre,
  MediaSummary,
  MediaType,
  RequestsError,
  SeeAllFilters,
  SortOrder,
} from "./types";

interface Props {
  type: MediaType;
  /** From a row's « Tout voir »; the default sort otherwise. */
  initial?: SeeAllFilters;
  onOpen: (media: MediaSummary) => void;
}

let { type, initial = DEFAULT_FILTERS, onOpen }: Props = $props();

let filters = $state<SeeAllFilters>(untrack(() => ({ ...initial })));
let items = $state<MediaSummary[]>([]);
let page = $state(0);
let totalPages = $state<number | null>(null);
let loading = $state(false);
let error = $state<RequestsError | null>(null);
let genres = $state<Genre[] | null>(null);
let genresFailed = $state(false);
let sentinel = $state<HTMLElement | null>(null);
let sortSelect = $state<HTMLSelectElement | null>(null);
let generation = 0;

const done = $derived(totalPages !== null && page >= totalPages);
const genre = $derived(genreChoice(filters));
const decade = $derived(decadeOf(filters));
const years = $derived(yearsLabel(filters));
const decadeList = decades(new Date().getFullYear());
const noun = $derived(type === "movie" ? "Films" : "Séries");

async function loadMore() {
  if (loading || done) return;
  const gen = generation;
  loading = true;
  error = null;
  try {
    const next = await requestsApi.browse(type, browseArgs(filters, page + 1));
    if (gen !== generation) return;
    items = mergePage(items, next.results);
    page = next.page;
    totalPages = next.totalPages;
  } catch (e) {
    if (gen === generation) error = toRequestsError(e);
  } finally {
    if (gen === generation) loading = false;
  }
}

/** New filters: the grid starts over from the first page. */
function apply(next: SeeAllFilters) {
  generation += 1;
  filters = next;
  items = [];
  page = 0;
  totalPages = null;
  loading = false;
  void loadMore();
}

async function reset() {
  apply({ ...DEFAULT_FILTERS });
  await tick();
  sortSelect?.focus();
}

/** Nearest scrolling ancestor: the next page starts at about 80 % of it. */
function scrollParent(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const y = getComputedStyle(p).overflowY;
    if (y === "auto" || y === "scroll") return p;
  }
  return null;
}

// Observed again after each page: a sentinel still in view asks for the next one.
$effect(() => {
  void page;
  if (!sentinel) return;
  const observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) void loadMore();
    },
    { root: scrollParent(sentinel), rootMargin: "0px 0px 25% 0px" },
  );
  observer.observe(sentinel);
  return () => observer.disconnect();
});

onMount(() => {
  void loadMore();
  requestsApi.genres(type).then(
    (g) => (genres = g),
    () => (genresFailed = true),
  );
});
</script>

<div class="toolbar" role="group" aria-label="Tri et filtres">
  <label class="ur-field">
    <span>Trier par</span>
    <select
      class="ur-select"
      bind:this={sortSelect}
      value={filters.sort ?? "popularity"}
      onchange={(e) =>
        apply({ ...filters, sort: e.currentTarget.value as SortOrder })}
    >
      {#each SORTS as s (s.key)}<option value={s.key}>{s.label}</option>{/each}
    </select>
  </label>
  <label class="ur-field">
    <span>Genre</span>
    <select
      class="ur-select"
      value={genre === null ? "" : String(genre)}
      aria-busy={genres === null && !genresFailed}
      onchange={(e) => {
        const v = e.currentTarget.value;
        if (v !== "mixed") apply(withGenre(filters, v ? Number(v) : null));
      }}
    >
      <option value="">{genresFailed ? "Genres indisponibles" : "Tous les genres"}</option>
      {#if genre === "mixed"}
        <option value="mixed" disabled>Plusieurs genres</option>
      {/if}
      {#each genres ?? [] as g (g.id)}<option value={String(g.id)}>{g.name}</option>{/each}
    </select>
  </label>
  <label class="ur-field">
    <span>Années</span>
    <select
      class="ur-select"
      value={decade !== null ? String(decade) : years ? "custom" : ""}
      onchange={(e) => {
        const v = e.currentTarget.value;
        if (v !== "custom") apply(withDecade(filters, v ? Number(v) : null));
      }}
    >
      <option value="">Toutes les années</option>
      {#if years && decade === null}<option value="custom" disabled>{years}</option>{/if}
      {#each decadeList as d (d)}<option value={String(d)}>Années {d}</option>{/each}
    </select>
  </label>
  <label class="ur-field">
    <span>Note minimale</span>
    <select
      class="ur-select"
      value={filters.rating ? String(filters.rating) : ""}
      onchange={(e) =>
        apply({
          ...filters,
          rating: e.currentTarget.value ? Number(e.currentTarget.value) : null,
        })}
    >
      <option value="">Toutes les notes</option>
      {#if filters.rating && !RATINGS.includes(filters.rating)}
        <option value={String(filters.rating)}>{filters.rating} et plus</option>
      {/if}
      {#each RATINGS as r (r)}<option value={String(r)}>{r} et plus</option>{/each}
    </select>
  </label>
  {#if canReset(filters)}
    <button type="button" class="ur-btn reset" onclick={reset}>Réinitialiser</button>
  {/if}
</div>

{#if done && items.length === 0}
  <div class="state">
    <p class="state-title">Aucun titre ne correspond à ces filtres</p>
    <button type="button" class="ur-btn" onclick={reset}>
      Réinitialiser les filtres
    </button>
  </div>
{:else}
  <ul class="ur-grid" aria-label={noun} aria-busy={loading}>
    {#each items as media (`${media.type}:${media.tmdbId}`)}
      <li><MediaCard {media} {onOpen} /></li>
    {/each}
    {#if loading}
      {#each { length: items.length === 0 ? 18 : 6 } as _, i (i)}
        <li aria-hidden="true"><div class="ur-skeleton"></div></li>
      {/each}
    {/if}
  </ul>
{/if}

{#if error}
  <div class="state" role="alert">
    <p>{error.message}</p>
    <button type="button" class="ur-btn" onclick={loadMore}>Réessayer</button>
  </div>
{/if}
<div class="sentinel" bind:this={sentinel} aria-hidden="true"></div>

<style>
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: end;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }
  .reset {
    height: 38px;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
    padding: var(--space-6) 0;
  }
  .state p {
    margin: 0;
  }
  .state-title {
    font-size: 17px;
    font-weight: 600;
  }
  .sentinel {
    height: 1px;
  }
</style>
