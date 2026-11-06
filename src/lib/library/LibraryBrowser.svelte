<script lang="ts">
// Signed-in shell: the sidebar with the search field, then the home page
// (ADR 0010), the paged poster grid of a library (DESIGN.md, Films/Series) or the
// search results (ADR 0011), and the detail page of the opened item over them.
import { onMount, tick, untrack } from "svelte";
import { episodeItem } from "../detail/format";
import ItemDetail from "../detail/ItemDetail.svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  EpisodeLink,
  SearchResults as Found,
  ItemsOptions,
  Library,
  MediaItem,
  PlaybackStart,
  SignedInUser,
  SortKey,
  WatchFilter,
  WatchItem,
} from "../emby/types";
import Home from "../home/Home.svelte";
import {
  cleanQuery,
  isSearchable,
  SEARCH_DEBOUNCE_MS,
  SEARCH_MAX_CHARS,
} from "../search/query";
import SearchResults from "../search/SearchResults.svelte";
import Settings from "../settings/Settings.svelte";
import {
  DEFAULT_OPTIONS,
  directionLabels,
  isDefault,
  isFiltered,
  loadOptions,
  noMatchLabel,
  SORTS,
  sameOptions,
  saveOptions,
  storageKey,
  watchChoices,
  withSort,
} from "./options";
import PosterCard from "./PosterCard.svelte";
import { appendPage, countLabel, emptyPager, PAGE_SIZE } from "./pager";

interface Props {
  user: SignedInUser;
  /** Bumped when the connection comes back (ADR 0012): failed areas reload. */
  refresh: number;
  signingOut: boolean;
  onSignOut: () => void;
  onOpenPlayer: () => void;
  /** Sets the session aside and shows the server form. */
  onChangeServer: () => void;
  /** Every error is reported: a 401 or a lost NAS changes the screen. */
  onError: (error: EmbyError) => void;
  /** A film or episode started playing: show the player over this view. */
  onPlay: (start: PlaybackStart) => void;
}

let {
  user,
  refresh,
  signingOut,
  onSignOut,
  onOpenPlayer,
  onChangeServer,
  onError,
  onPlay,
}: Props = $props();

let libraries = $state<Library[] | null>(null);
let librariesError = $state<EmbyError | null>(null);
/** The library shown in the grid; `null` is the home page. */
let selected = $state<Library | null>(null);
let pager = $state(emptyPager());
let loading = $state(false);
let pageError = $state<EmbyError | null>(null);
let scroller = $state<HTMLElement | null>(null);
let sentinel = $state<HTMLElement | null>(null);
// Bumped on every library or option switch: answers for older ones are dropped.
let generation = 0;
/** Sort and filters of the shown library (ADR 0013), remembered per library. */
let options = $state<ItemsOptions>({ ...DEFAULT_OPTIONS });
let genres = $state<string[] | null>(null);
let genresError = $state<EmbyError | null>(null);
let genresGeneration = 0;
let sortSelect = $state<HTMLSelectElement | null>(null);
const directions = $derived(directionLabels(options.sort));
let observer: IntersectionObserver | null = null;
let opened = $state<MediaItem | null>(null);
/** The settings screen replaces the content; a search still takes over. */
let settingsOpen = $state(false);
/** Card that opened the detail: an episode page may move to its neighbours. */
let openerId: string | null = null;
/** Neighbour button that replaced the episode page, to focus it again. */
let openedVia = $state<"previous" | "next" | null>(null);

let query = $state("");
let searchField = $state<HTMLInputElement | null>(null);
let found = $state<Found | null>(null);
let searchLoading = $state(false);
let searchError = $state<EmbyError | null>(null);
/** Results replace the current view while the field holds something. */
const cleaned = $derived(cleanQuery(query));
const searching = $derived(cleaned !== "");
// Bumped on every search or when leaving it: older answers are dropped.
let searchGeneration = 0;
let debounce: ReturnType<typeof setTimeout> | undefined;
/** Scroll of the view the search replaced, restored when leaving it. */
let scrollBeforeSearch: number | null = null;

/** Bumped after a favorite change on a detail page (ADR 0014). */
let favoritesVersion = $state(0);

function fail(e: unknown): EmbyError {
  const error = toEmbyError(e);
  onError(error);
  return error;
}

/** Back online: retry what failed; what is shown, and its scroll, stay. */
function retryFailed() {
  if (librariesError) void loadLibraries();
  if (pageError) void loadMore();
  if (searchError) void runSearch(true);
  if (genresError && selected) void loadGenres(selected);
}

let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(retryFailed);
});

async function loadLibraries() {
  librariesError = null;
  libraries = null;
  try {
    libraries = await embyApi.libraries();
  } catch (e) {
    librariesError = fail(e);
  }
}

function open(item: MediaItem) {
  opened = item;
  openerId = item.id;
  openedVia = null;
}

/** An episode found by the search (ADR 0020). */
function openEpisode(item: WatchItem) {
  open(episodeItem(item));
}

/** Previous or next episode in place of the open one (ADR 0016). */
function navigateEpisode(link: EpisodeLink, via: "previous" | "next") {
  opened = episodeItem(link);
  openedVia = via;
}

/** Back to the grid, focus on the card that opened the detail. */
async function closeDetail() {
  const id = openerId;
  opened = null;
  openerId = null;
  await tick();
  scroller
    ?.querySelector<HTMLElement>(`[data-item-id="${CSS.escape(id ?? "")}"]`)
    ?.focus({ preventScroll: true });
}

function onSearchInput(e: Event & { currentTarget: HTMLInputElement }) {
  const value = e.currentTarget.value;
  // Saved before the results replace the view, which resets the scroll.
  const entering = scrollBeforeSearch === null && cleanQuery(value) !== "";
  if (entering) scrollBeforeSearch = scroller?.scrollTop ?? 0;
  query = value;
  opened = null;
  clearTimeout(debounce);
  if (!searching) {
    void leaveSearch(true);
    return;
  }
  if (entering) scroller?.scrollTo({ top: 0 });
  if (!isSearchable(cleaned)) {
    // One letter finds nothing on Emby: no request, the results say so.
    searchGeneration += 1;
    found = null;
    searchError = null;
    searchLoading = false;
    return;
  }
  debounce = setTimeout(() => void runSearch(), SEARCH_DEBOUNCE_MS);
}

async function runSearch(again = false) {
  clearTimeout(debounce);
  const q = cleaned;
  if (!isSearchable(q)) return;
  if (!again && found?.query === q && !searchError) return;
  const gen = ++searchGeneration;
  searchLoading = true;
  searchError = null;
  try {
    const answer = await embyApi.search(q);
    if (gen !== searchGeneration) return;
    found = answer;
  } catch (e) {
    if (gen !== searchGeneration) return;
    searchError = fail(e);
  } finally {
    if (gen === searchGeneration) searchLoading = false;
  }
}

/** Empties the field; `restore` brings back the scroll of the view it replaced. */
async function leaveSearch(restore: boolean) {
  clearTimeout(debounce);
  searchGeneration += 1;
  query = "";
  found = null;
  searchError = null;
  searchLoading = false;
  const top = scrollBeforeSearch;
  scrollBeforeSearch = null;
  if (restore && top !== null) {
    await tick();
    scroller?.scrollTo({ top });
  }
}

function onSearchKeydown(e: KeyboardEvent) {
  if (e.key === "Escape" && query !== "") {
    // Handled here: the detail page does not close on this Escape.
    e.preventDefault();
    void leaveSearch(true);
  }
}

/** `Ctrl+F`, or `/` outside a text field, puts the focus in the search field. */
function onWindowKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || !searchField?.checkVisibility()) return;
  const target = e.target as HTMLElement | null;
  const typing =
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target?.isContentEditable === true;
  const ctrlF =
    e.ctrlKey && !e.altKey && !e.shiftKey && e.key.toLowerCase() === "f";
  const slash = e.key === "/" && !e.ctrlKey && !e.altKey && !typing;
  if (!ctrlF && !slash) return;
  e.preventDefault();
  searchField.focus();
  searchField.select();
}

function openSettings() {
  opened = null;
  if (searching) void leaveSearch(false);
  settingsOpen = true;
  scroller?.scrollTo({ top: 0 });
}

function showHome() {
  opened = null;
  settingsOpen = false;
  if (searching) void leaveSearch(false);
  if (selected === null) return;
  generation += 1;
  genresGeneration += 1;
  selected = null;
  pager = emptyPager();
  pageError = null;
  loading = false;
  scroller?.scrollTo({ top: 0 });
}

/** `localStorage` may be missing or refused: choices are then not kept. */
function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

const optionsKey = (library: Library) =>
  storageKey(user.server.id, user.userId, library.id);

/** Empties the grid and loads the first page for the current options. */
function restartGrid() {
  generation += 1;
  pager = emptyPager();
  pageError = null;
  loading = false;
  scroller?.scrollTo({ top: 0 });
  void loadMore();
}

function select(library: Library) {
  opened = null;
  settingsOpen = false;
  if (searching) void leaveSearch(false);
  if (selected?.id === library.id && pager.total !== null) return;
  selected = library;
  options = loadOptions(storage(), optionsKey(library), library.kind);
  void loadGenres(library);
  restartGrid();
}

/** Applies new options: saved for this library, grid reloaded from the top. */
function setOptions(next: ItemsOptions) {
  const library = selected;
  if (!library || sameOptions(next, options)) return;
  options = next;
  saveOptions(storage(), optionsKey(library), next);
  restartGrid();
}

/** The reset button disappears with the change: focus goes to the sort. */
async function resetOptions(keepSort: boolean) {
  setOptions(
    keepSort
      ? {
          ...DEFAULT_OPTIONS,
          sort: options.sort,
          descending: options.descending,
        }
      : { ...DEFAULT_OPTIONS },
  );
  await tick();
  sortSelect?.focus();
}

async function loadGenres(library: Library) {
  const gen = ++genresGeneration;
  genres = null;
  genresError = null;
  try {
    const names = await embyApi.genres(library.id);
    if (gen !== genresGeneration) return;
    genres = names;
    // A remembered genre the library no longer has would show an empty grid.
    if (options.genre !== null && !names.includes(options.genre)) {
      setOptions({ ...options, genre: null });
    }
  } catch (e) {
    if (gen !== genresGeneration) return;
    genresError = fail(e);
  }
}

async function loadMore() {
  const library = selected;
  if (!library || loading || pager.done) return;
  const gen = generation;
  loading = true;
  pageError = null;
  try {
    const page = await embyApi.items(
      library.id,
      pager.next,
      PAGE_SIZE,
      $state.snapshot(options),
    );
    if (gen !== generation) return;
    pager = appendPage(pager, page);
  } catch (e) {
    if (gen !== generation) return;
    pageError = fail(e);
  } finally {
    if (gen === generation) loading = false;
  }
  // Re-observing reports the sentinel at once: a tall window keeps filling.
  await tick();
  if (gen === generation && observer && sentinel && !pageError) {
    observer.unobserve(sentinel);
    observer.observe(sentinel);
  }
}

onMount(() => {
  void loadLibraries();
  return () => {
    observer?.disconnect();
    clearTimeout(debounce);
  };
});

$effect(() => {
  if (!scroller || !sentinel) return;
  const target = sentinel;
  observer = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) void loadMore();
    },
    { root: scroller, rootMargin: "0px 0px 800px 0px" },
  );
  observer.observe(target);
  return () => {
    observer?.disconnect();
    observer = null;
  };
});
</script>

<svelte:window onkeydown={onWindowKeydown} />

<div class="browser">
  <nav class="sidebar" aria-label="Bibliothèques">
    <p class="brand">Viewer</p>
    <form
      class="search-box"
      role="search"
      onsubmit={(e) => {
        e.preventDefault();
        void runSearch();
      }}
    >
      <label class="sr-only" for="search-field">Rechercher un film, une série ou un épisode</label>
      <svg viewBox="0 0 16 16" aria-hidden="true"
        ><circle cx="7" cy="7" r="4.5" /><path d="m10.5 10.5 3 3" /></svg
      >
      <input
        id="search-field"
        type="search"
        placeholder="Rechercher"
        autocomplete="off"
        spellcheck="false"
        enterkeyhint="search"
        maxlength={SEARCH_MAX_CHARS * 2}
        aria-keyshortcuts="Control+F /"
        bind:this={searchField}
        value={query}
        oninput={onSearchInput}
        onkeydown={onSearchKeydown}
      />
    </form>
    {#if libraries === null && !librariesError}
      <p class="side-note" aria-busy="true">Chargement…</p>
    {:else if libraries}
      <button
        type="button"
        class="home-link"
        aria-current={selected === null && !searching && !settingsOpen
          ? "page"
          : undefined}
        onclick={showHome}
      >
        Accueil
      </button>
      <ul class="libraries">
        {#each libraries as library (library.id)}
          <li>
            <button
              type="button"
              aria-current={selected?.id === library.id &&
              !searching &&
              !settingsOpen
                ? "page"
                : undefined}
              onclick={() => select(library)}
            >
              <span class="kind" aria-hidden="true"
                >{library.kind === "movies" ? "Films" : "Séries"}</span
              >
              {library.name}
            </button>
          </li>
        {/each}
      </ul>
    {/if}
    <div class="account">
      <p class="who">
        {user.userName}
        <span>{user.server.name}{user.server.encrypted ? "" : " · non chiffré"}</span>
      </p>
      <button
        type="button"
        class="quiet"
        aria-current={settingsOpen && !searching ? "page" : undefined}
        onclick={openSettings}
      >
        Réglages
      </button>
      <button type="button" class="quiet" onclick={onChangeServer}>
        Changer de serveur
      </button>
      {#if import.meta.env.DEV}
        <button type="button" class="quiet" onclick={onOpenPlayer}>
          Prototype de lecture
        </button>
      {/if}
      <button type="button" class="quiet" onclick={onSignOut} disabled={signingOut}>
        {signingOut ? "Déconnexion…" : "Se déconnecter"}
      </button>
    </div>
  </nav>

  <div class="pane">
  <main
    class="content"
    bind:this={scroller}
    aria-busy={loading}
    inert={opened !== null}
    aria-hidden={opened !== null ? "true" : undefined}
  >
    {#if librariesError}
      <div class="state" role="alert">
        <p class="state-title">Impossible de charger vos bibliothèques</p>
        <p>{librariesError.message}</p>
        {#if errorHint(librariesError)}<p class="hint">{errorHint(librariesError)}</p>{/if}
        <button type="button" onclick={loadLibraries}>Réessayer</button>
      </div>
    {:else if libraries && libraries.length === 0}
      <div class="state">
        <p class="state-title">Aucune bibliothèque de films ou de séries</p>
        <p>Votre compte n'a accès à aucune bibliothèque de ce type sur {user.server.name}.</p>
      </div>
    {:else if searching}
      <SearchResults
        query={cleaned}
        results={found}
        loading={searchLoading}
        error={searchError}
        onRetry={() => void runSearch(true)}
        onOpen={open}
        onOpenEpisode={openEpisode}
      />
    {:else if settingsOpen}
      <Settings />
    {:else if libraries && selected === null}
      <Home
        {libraries}
        userName={user.userName}
        onOpen={open}
        onShowLibrary={select}
        {onPlay}
        {refresh}
        {favoritesVersion}
        onError={fail}
      />
    {:else if selected}
      <header class="heading">
        <h1>{selected.name}</h1>
        <p class="count" aria-live="polite">
          {pager.total !== null ? countLabel(pager.total, selected.kind) : ""}
        </p>
      </header>

      <div class="toolbar" role="group" aria-label="Tri et filtres">
        <label class="field">
          <span>Trier par</span>
          <select
            bind:this={sortSelect}
            value={options.sort}
            onchange={(e) =>
              setOptions(withSort(options, e.currentTarget.value as SortKey))}
          >
            {#each SORTS as s (s.key)}
              <option value={s.key}>{s.label}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Ordre</span>
          <select
            value={options.descending ? "desc" : "asc"}
            onchange={(e) =>
              setOptions({
                ...options,
                descending: e.currentTarget.value === "desc",
              })}
          >
            <option value="asc">{directions.ascending}</option>
            <option value="desc">{directions.descending}</option>
          </select>
        </label>
        <label class="field">
          <span>Visionnage</span>
          <select
            value={options.watch}
            onchange={(e) =>
              setOptions({
                ...options,
                watch: e.currentTarget.value as WatchFilter,
              })}
          >
            {#each watchChoices(selected.kind) as w (w.key)}
              <option value={w.key}>{w.label}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Genre</span>
          <select
            value={options.genre ?? ""}
            aria-busy={genres === null && !genresError}
            onchange={(e) =>
              setOptions({ ...options, genre: e.currentTarget.value || null })}
          >
            <option value="">
              {genresError ? "Genres indisponibles" : "Tous les genres"}
            </option>
            {#if options.genre !== null && !genres?.includes(options.genre)}
              <option value={options.genre}>{options.genre}</option>
            {/if}
            {#each genres ?? [] as g (g)}
              <option value={g}>{g}</option>
            {/each}
          </select>
        </label>
        <button
          type="button"
          class="toggle"
          aria-pressed={options.favorites}
          onclick={() => setOptions({ ...options, favorites: !options.favorites })}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"
            ><path
              d="m8 1.8 1.9 3.9 4.3.6-3.1 3 .7 4.3L8 11.6l-3.8 2 .7-4.3-3.1-3 4.3-.6z"
            /></svg
          >
          Favoris
        </button>
        {#if !isDefault(options)}
          <button type="button" class="quiet reset" onclick={() => resetOptions(false)}>
            Réinitialiser
          </button>
        {/if}
      </div>

      {#if pager.total === 0 && isFiltered(options)}
        <div class="state">
          <p class="state-title">{noMatchLabel(selected.kind)}</p>
          <button type="button" onclick={() => resetOptions(true)}>
            Réinitialiser les filtres
          </button>
        </div>
      {:else if pager.total === 0}
        <div class="state">
          <p class="state-title">Cette bibliothèque est vide</p>
        </div>
      {:else}
        <ul class="grid" aria-label={selected.name}>
          {#each pager.items as item (item.id)}
            <li><PosterCard {item} onOpen={open} /></li>
          {/each}
          {#if loading}
            {#each { length: pager.items.length === 0 ? 18 : 6 } as _, i (i)}
              <li class="skeleton" aria-hidden="true">
                <div class="sk-poster"></div>
                <div class="sk-line"></div>
              </li>
            {/each}
          {/if}
        </ul>
      {/if}

      {#if pageError}
        <div class="state inline" role="alert">
          <p>{pageError.message}</p>
          {#if errorHint(pageError)}<p class="hint">{errorHint(pageError)}</p>{/if}
          <button type="button" onclick={loadMore}>Réessayer</button>
        </div>
      {:else if !pager.done && !loading && pager.total !== null}
        <div class="more">
          <button type="button" class="quiet" onclick={loadMore}>Afficher la suite</button>
        </div>
      {/if}
    {/if}
    <div class="sentinel" bind:this={sentinel} aria-hidden="true"></div>
  </main>
  {#if opened}
    {#key opened.id}
      <ItemDetail
        item={opened}
        {refresh}
        onClose={closeDetail}
        onError={fail}
        {onPlay}
        onFavoriteChange={() => favoritesVersion++}
        onNavigate={navigateEpisode}
        focusOnLoad={openedVia}
        backLabel={searching ? "Retour à la recherche" : undefined}
      />
    {/key}
  {/if}
  </div>
</div>

<style>
  .browser {
    height: 100%;
    display: grid;
    grid-template-columns: 240px 1fr;
    background: var(--color-bg);
  }
  .sidebar {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-6) var(--space-3);
    background: var(--color-surface);
    min-height: 0;
    overflow-y: auto;
  }
  .search-box {
    position: relative;
    margin: 0 var(--space-1);
  }
  .search-box svg {
    position: absolute;
    left: 10px;
    top: 50%;
    width: 16px;
    height: 16px;
    transform: translateY(-50%);
    fill: none;
    stroke: var(--color-text-muted);
    stroke-width: 1.6;
    stroke-linecap: round;
    pointer-events: none;
  }
  .search-box input {
    width: 100%;
    box-sizing: border-box;
    padding: var(--space-2) var(--space-2) var(--space-2) 32px;
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text);
    font: inherit;
  }
  .search-box input::placeholder {
    color: var(--color-text-muted);
  }
  .search-box input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 1px;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    padding: 0;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
    border: 0;
  }
  .brand {
    margin: 0 var(--space-3);
    font-size: 18px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }
  .side-note {
    margin: 0 var(--space-3);
    color: var(--color-text-muted);
  }
  .libraries {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 2px;
  }
  .libraries button,
  .home-link {
    width: 100%;
    text-align: left;
    background: transparent;
    color: var(--color-text-secondary);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    font: inherit;
    cursor: pointer;
    display: grid;
    transition: background var(--motion-fast);
  }
  .libraries button:hover,
  .home-link:hover {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .libraries button[aria-current="page"],
  .home-link[aria-current="page"] {
    background: var(--color-elevated);
    color: var(--color-text);
    box-shadow: inset 3px 0 0 var(--color-accent);
  }
  .kind {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--color-text-muted);
  }
  .account {
    margin-top: auto;
    display: grid;
    gap: var(--space-1);
  }
  .who {
    margin: 0 var(--space-3) var(--space-2);
    font-weight: 600;
    display: grid;
  }
  .who span {
    font-weight: 400;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button.quiet {
    text-align: left;
    background: transparent;
    color: var(--color-text-secondary);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    font: inherit;
    cursor: pointer;
  }
  button.quiet[aria-current="page"] {
    background: var(--color-elevated);
    color: var(--color-text);
    box-shadow: inset 3px 0 0 var(--color-accent);
  }
  button.quiet:hover:not(:disabled) {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  .pane {
    position: relative;
    min-height: 0;
    min-width: 0;
  }
  .content {
    height: 100%;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-6) clamp(var(--space-4), 3vw, 48px) 48px;
  }
  .heading {
    display: flex;
    align-items: baseline;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  .count {
    margin: 0;
    color: var(--color-text-muted);
  }
  .heading {
    margin-bottom: var(--space-4);
  }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: var(--space-3) var(--space-4);
    margin-bottom: var(--space-6);
  }
  .field {
    display: grid;
    gap: var(--space-1);
    font-size: 12px;
    color: var(--color-text-muted);
  }
  .field select {
    min-width: 140px;
    max-width: 220px;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--color-elevated);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
  }
  .field select:hover {
    border-color: var(--color-text-muted);
  }
  .field select:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 1px;
  }
  .toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--color-elevated);
    border-radius: var(--radius-sm);
    background: var(--color-surface);
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
  }
  .toggle svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.4;
    stroke-linejoin: round;
  }
  .toggle[aria-pressed="true"] {
    border-color: var(--color-accent);
    color: var(--color-text);
  }
  .toggle[aria-pressed="true"] svg {
    fill: var(--color-accent);
    stroke: var(--color-accent);
  }
  .toggle:hover {
    color: var(--color-text);
  }
  button.reset {
    font-size: 14px;
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
  }
  .state {
    max-width: 520px;
    margin: 10vh auto 0;
    text-align: center;
    color: var(--color-text-secondary);
  }
  .state.inline {
    margin-top: var(--space-6);
  }
  .state p {
    margin: var(--space-1) 0;
  }
  .state p:first-letter {
    text-transform: uppercase;
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
  .state button:not(.quiet) {
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
  .more {
    display: flex;
    justify-content: center;
    margin-top: var(--space-6);
  }
  .sentinel {
    height: 1px;
  }
  @media (max-width: 800px) {
    .browser {
      grid-template-columns: 1fr;
      grid-template-rows: auto 1fr;
    }
    .sidebar {
      flex-direction: row;
      align-items: center;
      padding: var(--space-2) var(--space-3);
      overflow-x: auto;
      overflow-y: hidden;
    }
    .brand,
    .kind,
    .who {
      display: none;
    }
    .libraries {
      display: flex;
    }
    .search-box {
      flex: 0 0 180px;
      margin: 0;
    }
    .libraries button,
    .home-link {
      white-space: nowrap;
      width: auto;
    }
    .account {
      margin: 0 0 0 auto;
      display: flex;
    }
    button.quiet {
      white-space: nowrap;
    }
  }
</style>
