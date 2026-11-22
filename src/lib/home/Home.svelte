<script lang="ts">
// Home page (DESIGN.md, Home): the hero, then "Reprendre"
// and "À suivre" play at once, then favorites and the latest additions
// of each library open their detail page. Rows load in parallel; every row is read
// again in place after a playback (played marks, episode counts), "Favoris" also
// after every favorite change. A "Reprendre" card can be taken out of the row, or
// marked played, from its menu. Every row is also read again
// in place after 5 min shown, or 30 s away from the app, so new titles show,
// and at once when the server announces a library change.
// Rows follow the order and visibility of Réglages › Accueil, read when
// the home opens; a hidden row is never requested. « Playlists »
// lists the user's video playlists, each opening its page.
import { onMount, tick, untrack } from "svelte";
import { patchPlayed, restoreItem, withoutItem } from "../detail/watched";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  HeroItem,
  Library,
  MediaItem,
  PlaybackStart,
  PlaybackStopped,
  PlaylistCard,
  WatchItem,
} from "../emby/types";
import PosterCard from "../library/PosterCard.svelte";
import {
  HOME_CHECK_MS,
  HOME_STALE_MS,
  HOME_STALE_ON_RETURN_MS,
  isStale,
} from "./autoRefresh";
import { watchTitle, withoutDuplicates } from "./format";
import Hero from "./Hero.svelte";
import PlaylistPoster from "./PlaylistPoster.svelte";
import Row from "./Row.svelte";
import {
  defaultRows,
  FAVORITES_ROW,
  type HomeRowPrefs,
  NEXT_UP_ROW,
  PLAYLISTS_ROW,
  RESUME_ROW,
  visibleRows,
} from "./rows";
import WatchCard from "./WatchCard.svelte";

interface Props {
  libraries: Library[];
  userName: string;
  onOpen: (item: MediaItem) => void;
  onShowLibrary: (library: Library) => void;
  onOpenPlaylist: (playlist: PlaylistCard) => void;
  onPlay: (start: PlaybackStart) => void;
  /** Bumped when the connection comes back. */
  refresh: number;
  /** Bumped when a favorite changed on a detail page: "Favoris" is read again. */
  favoritesVersion: number;
  /** Reports the error upward (a lost server or a 401 changes the screen) and returns it. */
  onError: (e: unknown) => EmbyError;
  /** Order and hidden rows, read once when the home opens. */
  rowPrefs: HomeRowPrefs;
}

let {
  libraries,
  userName,
  onOpen,
  onShowLibrary,
  onOpenPlaylist,
  onPlay,
  refresh,
  favoritesVersion,
  onError,
  rowPrefs,
}: Props = $props();

const shown = $derived(visibleRows(defaultRows(libraries), rowPrefs));
const shows = (key: string) => shown.some((r) => r.key === key);
/** Libraries whose « Ajoutés récemment » row is shown: only these are requested. */
const shownLibraries = $derived(
  shown.flatMap((r) => (r.library ? [r.library] : [])),
);

interface RowState<T> {
  items: T[] | null;
  error: EmbyError | null;
}

function loadingRow<T>(): RowState<T> {
  return { items: null, error: null };
}

/** A hidden row: never requested, counts as empty. */
function hiddenRow<T>(): RowState<T> {
  return { items: [], error: null };
}

let hero = $state<RowState<HeroItem>>(loadingRow());
let inProgress = $state<RowState<WatchItem>>(loadingRow());
let nextUp = $state<RowState<WatchItem>>(loadingRow());
let favorites = $state<RowState<MediaItem>>(loadingRow());
let latest = $state<Record<string, RowState<MediaItem>>>({});
let playlists = $state<RowState<PlaylistCard>>(loadingRow());
let playlistsGeneration = 0;
let starting = $state<string | null>(null);
let playError = $state<{ title: string; error: EmbyError } | null>(null);
/** "Reprendre" card being taken out: one at a time. */
let removing = $state<string | null>(null);
let removeError = $state<{
  title: string;
  played: boolean;
  error: EmbyError;
} | null>(null);
let resumeList = $state<HTMLElement | null>(null);
// Bumped by every reload of the watch rows: older answers are dropped.
let watchGeneration = 0;
let favoritesGeneration = 0;
const latestGeneration: Record<string, number> = {};
/** When every row was last asked for. */
let loadedAt = Date.now();

const nextUpShown = $derived(
  nextUp.items ? withoutDuplicates(nextUp.items, inProgress.items ?? []) : null,
);
const watchLoading = $derived(
  (inProgress.items === null && !inProgress.error) ||
    (nextUp.items === null && !nextUp.error),
);
const everythingEmpty = $derived(
  shown.length > 0 &&
    hero.items?.length === 0 &&
    inProgress.items?.length === 0 &&
    nextUpShown?.length === 0 &&
    favorites.items?.length === 0 &&
    playlists.items?.length === 0 &&
    shownLibraries.every((l) => latest[l.id]?.items?.length === 0),
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
  if (shows(RESUME_ROW)) {
    void loadRow(embyApi.continueWatching, (r) => (inProgress = r), current);
  } else inProgress = hiddenRow();
  if (shows(NEXT_UP_ROW)) {
    void loadRow(embyApi.nextUp, (r) => (nextUp = r), current);
  } else nextUp = hiddenRow();
}

/** Read again in place: the cards shown stay until the answer comes. */
function loadFavorites() {
  if (!shows(FAVORITES_ROW)) {
    favorites = hiddenRow();
    return;
  }
  const gen = ++favoritesGeneration;
  void loadRow(
    embyApi.favorites,
    (r) => (favorites = r),
    () => gen === favoritesGeneration,
  );
}

/** Read again in place: the cards shown stay until the answer comes. */
function loadPlaylists() {
  if (!shows(PLAYLISTS_ROW)) {
    playlists = hiddenRow();
    return;
  }
  const gen = ++playlistsGeneration;
  void loadRow(
    embyApi.homePlaylists,
    (r) => (playlists = r),
    () => gen === playlistsGeneration,
  );
}

function retryPlaylists() {
  playlists = loadingRow();
  loadPlaylists();
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
  loadedAt = Date.now();
  loadWatchRows();
  loadFavorites();
  loadPlaylists();
  for (const library of shownLibraries) loadLatest(library, true);
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

/**
 * Takes a card out of "Reprendre" at once, then asks the server: hidden from
 * resume, or marked played. On failure the card comes back where it was. The
 * event that follows reads the row again, so the server has the last word.
 */
async function takeOut(item: WatchItem, played: boolean) {
  const shown = inProgress.items;
  if (removing || !shown) return;
  const { items, removed } = withoutItem(shown, item.id);
  if (!removed) return;
  removing = item.id;
  removeError = null;
  const neighbour = shown[removed.index + 1] ?? shown[removed.index - 1];
  const hadFocus = resumeList?.contains(document.activeElement) ?? false;
  inProgress = { items, error: null };
  if (hadFocus && neighbour) {
    await tick();
    resumeList
      ?.querySelector<HTMLElement>(
        `button.card[data-item-id="${CSS.escape(neighbour.id)}"]`,
      )
      ?.focus();
  }
  try {
    if (played) await embyApi.setPlayed(item.id, true);
    else await embyApi.hideFromResume(item.id);
  } catch (e) {
    if (inProgress.items) {
      inProgress = {
        items: restoreItem(inProgress.items, removed),
        error: null,
      };
    }
    removeError = { title: watchTitle(item), played, error: onError(e) };
  } finally {
    removing = null;
  }
}

/** Watched state changed: posters patched at once, then every row read again. */
function onStopped(state: PlaybackStopped) {
  if (favorites.items) patchPlayed(favorites.items, state);
  for (const row of Object.values(latest)) {
    if (row.items) patchPlayed(row.items, state);
  }
  afterPlayback();
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
    if (playlists.error) retryPlaylists();
    for (const library of shownLibraries) {
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

/** New titles on the server: rows read again in place while the app is shown. */
function reloadIfStale(after: number) {
  if (document.visibilityState !== "visible") return;
  if (isStale(loadedAt, Date.now(), after)) afterPlayback();
}

function onReturn() {
  reloadIfStale(HOME_STALE_ON_RETURN_MS);
}

onMount(() => {
  loadedAt = Date.now();
  loadWatchRows();
  loadFavorites();
  loadPlaylists();
  for (const library of shownLibraries) loadLatest(library);
  const unlisten = embyApi.onPlaybackStopped(onStopped);
  // Without the socket, the timer and the return to the app still refresh.
  const unwatch = embyApi.watchLibrary(afterPlayback).catch(() => null);
  const timer = setInterval(() => reloadIfStale(HOME_STALE_MS), HOME_CHECK_MS);
  window.addEventListener("focus", onReturn);
  document.addEventListener("visibilitychange", onReturn);
  return () => {
    clearInterval(timer);
    window.removeEventListener("focus", onReturn);
    document.removeEventListener("visibilitychange", onReturn);
    void unlisten.then((stop) => stop());
    void unwatch.then((stop) => stop?.());
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
  <!-- The hero opens the page: the heading is for screen readers. -->
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

  {#if removeError}
    <div class="play-error" role="alert">
      <p class="error-title">
        {removeError.played
          ? `« ${removeError.title} » n'a pas été marqué comme vu`
          : `« ${removeError.title} » n'a pas été retiré de Reprendre`}
      </p>
      <p>{removeError.error.message}</p>
      {#if errorHint(removeError.error)}<p class="hint">{errorHint(removeError.error)}</p>{/if}
      <button type="button" class="secondary" onclick={() => (removeError = null)}>Fermer</button>
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

  {#each shown as homeRow (homeRow.key)}
    {#if homeRow.key === RESUME_ROW}
      {#if inProgress.error}
        <section class="row-failed" aria-labelledby="row-resume">
          <h2 id="row-resume">Reprendre</h2>
          {@render rowError(inProgress.error, loadWatchRows)}
        </section>
      {:else if inProgress.items === null}
        <Row title="Reprendre" id="row-resume" wide>
          {@render skeleton(true, 4)}
        </Row>
      {:else if inProgress.items.length}
        <div class="resume-row" bind:this={resumeList}>
          <Row title="Reprendre" id="row-resume" wide>
            {#each inProgress.items as item (item.id)}
              <li>
                <WatchCard
                  {item}
                  busy={starting === item.id}
                  disabled={starting !== null}
                  onPlay={play}
                  menuContext="resume"
                  onMarkPlayed={(i) => takeOut(i, true)}
                  onRemoveFromResume={(i) => takeOut(i, false)}
                />
              </li>
            {/each}
          </Row>
        </div>
      {/if}
    {:else if homeRow.key === NEXT_UP_ROW}
      {#if nextUp.error}
        <section class="row-failed" aria-labelledby="row-next">
          <h2 id="row-next">À suivre</h2>
          {@render rowError(nextUp.error, loadWatchRows)}
        </section>
      {:else if nextUpShown === null}
        <Row title="À suivre" id="row-next" wide>
          {@render skeleton(true, 4)}
        </Row>
      {:else if nextUpShown.length}
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
    {:else if homeRow.key === FAVORITES_ROW}
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
    {:else if homeRow.key === PLAYLISTS_ROW}
      {#if playlists.error}
        <section class="row-failed" aria-labelledby="row-playlists">
          <h2 id="row-playlists">{homeRow.label}</h2>
          {@render rowError(playlists.error, retryPlaylists)}
        </section>
      {:else if !playlists.items || playlists.items.length > 0}
        <Row title={homeRow.label} id="row-playlists">
          {#if playlists.items}
            {#each playlists.items as playlist (playlist.id)}
              <li><PlaylistPoster {playlist} onOpen={onOpenPlaylist} /></li>
            {/each}
          {:else}
            {@render skeleton(false, 8)}
          {/if}
        </Row>
      {/if}
    {:else if homeRow.library}
      {@const library = homeRow.library}
      {@const row = latest[library.id]}
      {#if row?.error}
        <section class="row-failed" aria-labelledby="row-latest-{library.id}">
          <h2 id="row-latest-{library.id}">{homeRow.label}</h2>
          {@render rowError(row.error, () => loadLatest(library))}
        </section>
      {:else if !row?.items || row.items.length > 0}
        <Row title={homeRow.label} id="row-latest-{library.id}">
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
    {/if}
  {/each}

  {#if shown.length === 0}
    <div class="state">
      <p class="state-title">Toutes les rangées sont masquées</p>
      <p>Choisis celles à afficher dans Réglages › Accueil.</p>
    </div>
  {/if}
  {#if everythingEmpty}
    <div class="state">
      <p class="state-title">Rien à afficher pour l'instant</p>
      <p>Tes bibliothèques ne contiennent encore aucun film ni épisode récent.</p>
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
