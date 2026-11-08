<script lang="ts">
// Home page (DESIGN.md, Home; ADR 0010): the hero (ADR 0015), then "Reprendre"
// and "À suivre" play at once, then favorites (ADR 0014) and the latest additions
// of each library open their detail page. Rows load in parallel; every row is read
// again in place after a playback (played marks, episode counts), "Favoris" also
// after every favorite change.
import { onMount, untrack } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  HeroItem,
  Library,
  MediaItem,
  PlaybackStart,
  WatchItem,
} from "../emby/types";
import PosterCard from "../library/PosterCard.svelte";
import { watchTitle, withoutDuplicates } from "./format";
import Hero from "./Hero.svelte";
import Row from "./Row.svelte";
import WatchCard from "./WatchCard.svelte";

interface Props {
  libraries: Library[];
  userName: string;
  onOpen: (item: MediaItem) => void;
  onShowLibrary: (library: Library) => void;
  onPlay: (start: PlaybackStart) => void;
  /** Bumped when the connection comes back (ADR 0012). */
  refresh: number;
  /** Bumped when a favorite changed on a detail page: "Favoris" is read again. */
  favoritesVersion: number;
  /** Reports the error upward (a lost server or a 401 changes the screen) and returns it. */
  onError: (e: unknown) => EmbyError;
}

let {
  libraries,
  userName,
  onOpen,
  onShowLibrary,
  onPlay,
  refresh,
  favoritesVersion,
  onError,
}: Props = $props();

interface RowState<T> {
  items: T[] | null;
  error: EmbyError | null;
}

function loadingRow<T>(): RowState<T> {
  return { items: null, error: null };
}

let hero = $state<RowState<HeroItem>>(loadingRow());
let inProgress = $state<RowState<WatchItem>>(loadingRow());
let nextUp = $state<RowState<WatchItem>>(loadingRow());
let favorites = $state<RowState<MediaItem>>(loadingRow());
let latest = $state<Record<string, RowState<MediaItem>>>({});
let starting = $state<string | null>(null);
let playError = $state<{ title: string; error: EmbyError } | null>(null);
// Bumped by every reload of the watch rows: older answers are dropped.
let watchGeneration = 0;
let favoritesGeneration = 0;
const latestGeneration: Record<string, number> = {};

const nextUpShown = $derived(
  nextUp.items ? withoutDuplicates(nextUp.items, inProgress.items ?? []) : null,
);
const watchLoading = $derived(
  (inProgress.items === null && !inProgress.error) ||
    (nextUp.items === null && !nextUp.error),
);
const everythingEmpty = $derived(
  hero.items?.length === 0 &&
    inProgress.items?.length === 0 &&
    nextUpShown?.length === 0 &&
    favorites.items?.length === 0 &&
    libraries.every((l) => latest[l.id]?.items?.length === 0),
);

async function loadRow<T>(
  fetch: () => Promise<T[]>,
  set: (row: RowState<T>) => void,
  isCurrent: () => boolean = () => true,
) {
  try {
    const items = await fetch();
    if (isCurrent()) set({ items, error: null });
  } catch (e) {
    if (isCurrent()) set({ items: null, error: onError(e) });
  }
}

/** Hero, "Reprendre" and "À suivre": read again in place after a playback. */
function loadWatchRows() {
  const gen = ++watchGeneration;
  const current = () => gen === watchGeneration;
  void loadRow(embyApi.hero, (r) => (hero = r), current);
  void loadRow(embyApi.continueWatching, (r) => (inProgress = r), current);
  void loadRow(embyApi.nextUp, (r) => (nextUp = r), current);
}

/** Read again in place: the cards shown stay until the answer comes. */
function loadFavorites() {
  const gen = ++favoritesGeneration;
  void loadRow(
    embyApi.favorites,
    (r) => (favorites = r),
    () => gen === favoritesGeneration,
  );
}

function retryFavorites() {
  favorites = loadingRow();
  loadFavorites();
}

/** `inPlace` keeps the cards shown until the answer comes (after a playback). */
function loadLatest(library: Library, inPlace = false) {
  const gen = (latestGeneration[library.id] ?? 0) + 1;
  latestGeneration[library.id] = gen;
  if (!inPlace) latest[library.id] = loadingRow();
  void loadRow(
    () => embyApi.latest(library.id),
    // A block body: an assignment to a proxy property is not its value.
    (r) => {
      latest[library.id] = r;
    },
    () => gen === latestGeneration[library.id],
  );
}

/** Played state and episode counts changed: every row showing them is read again. */
function afterPlayback() {
  loadWatchRows();
  loadFavorites();
  for (const library of libraries) loadLatest(library, true);
}

async function start(id: string, resume: boolean, title: string) {
  if (starting) return;
  starting = id;
  playError = null;
  try {
    onPlay(await embyApi.play(id, resume));
  } catch (e) {
    playError = { title, error: onError(toEmbyError(e)) };
  } finally {
    starting = null;
  }
}

function play(item: WatchItem) {
  void start(item.id, item.resume !== null, watchTitle(item));
}

function playHero(item: HeroItem) {
  if (item.playId)
    void start(item.playId, item.resume !== null, item.detail.name);
}

function retryHero() {
  hero = loadingRow();
  void loadRow(embyApi.hero, (r) => (hero = r));
}

// Back online: the watch rows are read again in place, failed rows retried.
let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(() => {
    loadWatchRows();
    loadFavorites();
    for (const library of libraries) {
      if (latest[library.id]?.error) loadLatest(library);
    }
  });
});

let seenFavorites = untrack(() => favoritesVersion);
$effect(() => {
  if (favoritesVersion === seenFavorites) return;
  seenFavorites = favoritesVersion;
  untrack(loadFavorites);
});

onMount(() => {
  loadWatchRows();
  loadFavorites();
  for (const library of libraries) loadLatest(library);
  const unlisten = embyApi.onPlaybackStopped(afterPlayback);
  return () => {
    void unlisten.then((stop) => stop());
  };
});
</script>

{#snippet rowError(error: EmbyError, retry: () => void)}
  <div class="row-error" role="alert">
    <p>{error.message}</p>
    {#if errorHint(error)}<p class="hint">{errorHint(error)}</p>{/if}
    <button type="button" class="secondary" onclick={retry}>Réessayer</button>
  </div>
{/snippet}

{#snippet skeleton(wide: boolean, count: number)}
  {#each { length: count } as _, i (i)}
    <li class="skeleton" class:wide aria-hidden="true">
      <div class="sk-image"></div>
      <div class="sk-line"></div>
    </li>
  {/each}
{/snippet}

<div class="home" aria-busy={watchLoading}>
  <!-- The hero opens the page (ADR 0031): the heading is for screen readers. -->
  <header class="sr-only">
    <h1>Accueil</h1>
    <p>Bonjour {userName}</p>
  </header>

  {#if playError}
    <div class="play-error" role="alert">
      <p class="error-title">Impossible de lire « {playError.title} »</p>
      <p>{playError.error.message}</p>
      {#if errorHint(playError.error)}<p class="hint">{errorHint(playError.error)}</p>{/if}
      <button type="button" class="secondary" onclick={() => (playError = null)}>Fermer</button>
    </div>
  {/if}

  {#if hero.error}
    <section class="row-failed" aria-labelledby="hero-failed">
      <h2 id="hero-failed">À la une</h2>
      {@render rowError(hero.error, retryHero)}
    </section>
  {:else if hero.items === null}
    <div class="hero-skeleton" aria-hidden="true"></div>
  {:else}
    <Hero items={hero.items} {starting} onPlay={playHero} {onOpen} />
  {/if}

  {#if watchLoading}
    <Row title="Reprendre" id="row-loading" wide>
      {@render skeleton(true, 4)}
    </Row>
  {:else}
    {#if inProgress.error}
      <section class="row-failed" aria-labelledby="row-resume">
        <h2 id="row-resume">Reprendre</h2>
        {@render rowError(inProgress.error, loadWatchRows)}
      </section>
    {:else if inProgress.items?.length}
      <Row title="Reprendre" id="row-resume" wide>
        {#each inProgress.items as item (item.id)}
          <li>
            <WatchCard
              {item}
              busy={starting === item.id}
              disabled={starting !== null}
              onPlay={play}
            />
          </li>
        {/each}
      </Row>
    {/if}

    {#if nextUp.error}
      <section class="row-failed" aria-labelledby="row-next">
        <h2 id="row-next">À suivre</h2>
        {@render rowError(nextUp.error, loadWatchRows)}
      </section>
    {:else if nextUpShown?.length}
      <Row title="À suivre" id="row-next" wide>
        {#each nextUpShown as item (item.id)}
          <li>
            <WatchCard
              {item}
              busy={starting === item.id}
              disabled={starting !== null}
              onPlay={play}
            />
          </li>
        {/each}
      </Row>
    {/if}
  {/if}

  {#if favorites.error}
    <section class="row-failed" aria-labelledby="row-favorites">
      <h2 id="row-favorites">Favoris</h2>
      {@render rowError(favorites.error, retryFavorites)}
    </section>
  {:else if !favorites.items || favorites.items.length > 0}
    <Row title="Favoris" id="row-favorites">
      {#if favorites.items}
        {#each favorites.items as item (item.id)}
          <li><PosterCard {item} onOpen={onOpen} /></li>
        {/each}
      {:else}
        {@render skeleton(false, 8)}
      {/if}
    </Row>
  {/if}

  {#each libraries as library (library.id)}
    {@const row = latest[library.id]}
    {#if row?.error}
      <section class="row-failed" aria-labelledby="row-latest-{library.id}">
        <h2 id="row-latest-{library.id}">Ajoutés récemment · {library.name}</h2>
        {@render rowError(row.error, () => loadLatest(library))}
      </section>
    {:else if !row?.items || row.items.length > 0}
      <Row title="Ajoutés récemment · {library.name}" id="row-latest-{library.id}">
        {#snippet action()}
          <button
            type="button"
            class="see-all"
            aria-label="Tout voir dans {library.name}"
            onclick={() => onShowLibrary(library)}>Tout voir</button
          >
        {/snippet}
        {#if row?.items}
          {#each row.items as item (item.id)}
            <li><PosterCard {item} onOpen={onOpen} /></li>
          {/each}
        {:else}
          {@render skeleton(false, 8)}
        {/if}
      </Row>
    {/if}
  {/each}

  {#if everythingEmpty}
    <div class="state">
      <p class="state-title">Rien à afficher pour l'instant</p>
      <p>Vos bibliothèques ne contiennent encore aucun film ni épisode récent.</p>
    </div>
  {/if}
</div>

<style>
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .row-failed {
    margin-bottom: 32px;
  }
  .row-failed h2 {
    margin: 0 0 var(--space-2);
    font-size: 22px;
    font-weight: 600;
  }
  .row-error,
  .play-error {
    max-width: 640px;
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background: var(--color-surface);
    color: var(--color-text-secondary);
  }
  .play-error {
    margin-bottom: var(--space-6);
    border-left: 3px solid var(--color-danger);
  }
  .row-error p,
  .play-error p {
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
  button.secondary {
    margin-top: var(--space-2);
    background: var(--fill-glass);
    color: var(--color-text);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  button.secondary:hover {
    background: var(--fill-glass-hover);
  }
  .see-all {
    background: transparent;
    border: 0;
    border-radius: var(--radius-pill);
    padding: var(--space-1) var(--space-3);
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
  }
  .see-all:hover {
    background: rgb(255 255 255 / 0.1);
    color: var(--color-text);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  /* Same box as the hero: full bleed under the navigation pill. */
  .hero-skeleton {
    height: clamp(420px, 68vh, 760px);
    margin: -76px calc(-1 * var(--gutter, 0px)) -48px;
    background: linear-gradient(180deg, var(--color-surface), var(--color-bg));
    animation: pulse 1.4s ease-in-out infinite;
  }
  .skeleton .sk-image {
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    background: var(--color-surface);
    animation: pulse 1.4s ease-in-out infinite;
  }
  .skeleton.wide .sk-image {
    aspect-ratio: 16 / 9;
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
    .hero-skeleton,
    .skeleton .sk-image,
    .skeleton .sk-line {
      animation: none;
    }
  }
  .state {
    max-width: 520px;
    margin: 10vh auto 0;
    text-align: center;
    color: var(--color-text-secondary);
  }
  .state-title {
    color: var(--color-text);
    font-size: 18px;
    font-weight: 600;
  }
</style>
