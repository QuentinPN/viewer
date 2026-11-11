<script lang="ts">
// Signed-in shell: the floating navigation pill (ADR 0031) with the search field,
// then the home page
// (ADR 0010), the paged poster grid of a library (DESIGN.md, Films/Series) or the
// search results (ADR 0011), and the detail page of the opened item over them.
import { onMount, tick, untrack } from "svelte";
import { episodeItem } from "../detail/format";
import ItemDetail from "../detail/ItemDetail.svelte";
import { patchPlayed } from "../detail/watched";
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
import CardMenu from "../menu/CardMenu.svelte";
import { CardMenuState, provideCardMenu } from "../menu/menuState.svelte";
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
  /** Every error is reported: a 401 or a lost server changes the screen. */
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

/** The cards' menu (ADR 0100), shared by every card this screen renders. */
const cardMenu = new CardMenuState();
provideCardMenu(cardMenu);

function favoriteChanged(itemId: string, favorite: boolean) {
  favoritesVersion++;
  cardMenu.noteFavorite(itemId, favorite);
}

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

let accountMenu = $state<HTMLElement | null>(null);
let browser = $state<HTMLElement | null>(null);

/** Closes the account menu, then runs its action. */
function fromMenu(action: () => void) {
  accountMenu?.hidePopover();
  action();
}

// Many libraries: only their list scrolls, so search, Accueil, Réglages and the
// account stay in the pill. Its edges fade where libraries are hidden.
let libraryList = $state<HTMLElement | null>(null);
let hiddenBefore = $state(false);
let hiddenAfter = $state(false);

function updateListEdges() {
  if (!libraryList) return;
  const { scrollLeft, scrollWidth, clientWidth } = libraryList;
  hiddenBefore = scrollLeft > 1;
  hiddenAfter = scrollLeft + clientWidth < scrollWidth - 1;
}

$effect(() => {
  const list = libraryList;
  if (!list) return;
  // A mouse wheel only scrolls vertically: it scrolls the list sideways.
  const onWheel = (e: WheelEvent) => {
    if (list.scrollWidth <= list.clientWidth) return;
    if (Math.abs(e.deltaY) <= Math.abs(e.deltaX)) return;
    e.preventDefault();
    list.scrollLeft += e.deltaY;
  };
  const resize = new ResizeObserver(updateListEdges);
  resize.observe(list);
  list.addEventListener("wheel", onWheel, { passive: false });
  return () => {
    resize.disconnect();
    list.removeEventListener("wheel", onWheel);
  };
});

// The current library stays in view (Ctrl+number, return from a detail page).
$effect(() => {
  const id = selected?.id;
  if (!libraryList || !id || !libraries) return;
  void tick().then(() =>
    libraryList
      ?.querySelector<HTMLElement>(`[data-library-id="${CSS.escape(id)}"]`)
      ?.scrollIntoView({ block: "nearest", inline: "nearest" }),
  );
});

/** `Ctrl+1` shows the home page, `Ctrl+2`… the libraries in the order shown. */
function onNumberShortcut(e: KeyboardEvent): boolean {
  if (!e.ctrlKey || e.altKey || e.shiftKey || !libraries) return false;
  const n = Number.parseInt(e.key, 10);
  if (!(n >= 1 && n <= libraries.length + 1)) return false;
  const library = n === 1 ? null : libraries[n - 2];
  if (library) select(library);
  else showHome();
  return true;
}

/**
 * `Ctrl+F`, or `/` outside a text field, puts the focus in the search field. A
 * detail page hides the navigation (ADR 0031): it closes first.
 */
function onWindowKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || !browser?.checkVisibility()) return;
  if (onNumberShortcut(e)) {
    e.preventDefault();
    return;
  }
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
  void focusSearch();
}

async function focusSearch() {
  if (opened) {
    opened = null;
    openerId = null;
    await tick();
  }
  searchField?.focus();
  searchField?.select();
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
  // Posters shown follow a watched change made anywhere (ADR 0096).
  const unlisten = embyApi.onPlaybackStopped((state) => {
    patchPlayed(pager.items, state);
    if (found) {
      patchPlayed(found.movies, state);
      patchPlayed(found.series, state);
    }
  });
  return () => {
    void unlisten.then((stop) => stop());
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

<div class="browser" bind:this={browser}>
  <nav class="topnav" aria-label="Bibliothèques" hidden={opened !== null}>
    <div class="pill">
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
          title="Rechercher (Ctrl+F)"
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
        <span class="nav-note" aria-busy="true">Chargement…</span>
      {:else if libraries}
        <button
          type="button"
          class="home-link"
          aria-current={selected === null && !searching && !settingsOpen
            ? "page"
            : undefined}
          aria-keyshortcuts="Control+1"
          title="Accueil (Ctrl+1)"
          onclick={showHome}
        >
          Accueil
        </button>
        <ul
          class="libraries"
          class:fade-start={hiddenBefore}
          class:fade-end={hiddenAfter}
          bind:this={libraryList}
          onscroll={updateListEdges}
        >
          {#each libraries as library, i (library.id)}
            <li>
              <button
                type="button"
                data-library-id={library.id}
                aria-current={selected?.id === library.id &&
                !searching &&
                !settingsOpen
                  ? "page"
                  : undefined}
                aria-keyshortcuts={i < 8 ? `Control+${i + 2}` : undefined}
                title={i < 8 ? `${library.name} (Ctrl+${i + 2})` : library.name}
                onclick={() => select(library)}
              >
                <!-- Kind of library, read by the CDP scenarios; not shown. -->
                <span class="kind" hidden
                  >{library.kind === "movies" ? "Films" : "Séries"}</span
                >
                {library.name}
              </button>
            </li>
          {/each}
        </ul>
      {/if}
      <button
        type="button"
        class="icon"
        aria-label="Réglages"
        title="Réglages"
        aria-current={settingsOpen && !searching ? "page" : undefined}
        onclick={openSettings}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"
          ><circle cx="12" cy="12" r="3" /><path
            d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
          /></svg
        >
      </button>
      <button
        type="button"
        class="icon avatar"
        popovertarget="account-menu"
        aria-label="Compte : {user.userName}"
        title="{user.userName} · {user.server.name}"
      >
        <span aria-hidden="true">{user.userName.slice(0, 1).toUpperCase()}</span>
      </button>
    </div>
    <div id="account-menu" class="account-menu" popover bind:this={accountMenu}>
      <p class="who">
        {user.userName}
        <span>{user.server.name}{user.server.encrypted ? "" : " · non chiffré"}</span>
      </p>
      <button type="button" class="menu-item" onclick={() => fromMenu(onChangeServer)}>
        Changer de serveur
      </button>
      {#if import.meta.env.DEV}
        <button type="button" class="menu-item" onclick={() => fromMenu(onOpenPlayer)}>
          Prototype de lecture
        </button>
      {/if}
      <button
        type="button"
        class="menu-item"
        onclick={() => fromMenu(onSignOut)}
        disabled={signingOut}
      >
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
        onFavoriteChange={favoriteChanged}
        onNavigate={navigateEpisode}
        focusOnLoad={openedVia}
        backLabel={searching ? "Retour à la recherche" : undefined}
        versionScope="{user.server.id}:{user.userId}"
      />
    {/key}
  {/if}
  </div>
  <CardMenu menu={cardMenu} {onPlay} onError={fail} onFavoriteChange={favoriteChanged} />
</div>

<style>
  .browser {
    position: relative;
    height: 100%;
    background: var(--color-bg);
  }
  /* Floats over the content, which scrolls underneath (ADR 0031). */
  .topnav {
    position: absolute;
    inset: var(--space-2) 0 auto;
    z-index: 5;
    display: flex;
    justify-content: center;
    padding: 0 var(--space-4);
    pointer-events: none;
  }
  .topnav[hidden] {
    display: none;
  }
  .pill {
    pointer-events: auto;
    max-width: 100%;
    display: flex;
    align-items: center;
    gap: var(--space-1);
    padding: 6px;
    border-radius: var(--radius-pill);
    background: var(--material);
    backdrop-filter: var(--material-blur);
    border: 1px solid var(--separator);
    box-shadow: 0 10px 30px rgb(0 0 0 / 0.35);
  }
  .search-box {
    position: relative;
    flex: 0 0 auto;
  }
  .search-box svg {
    position: absolute;
    left: 12px;
    top: 50%;
    width: 16px;
    height: 16px;
    transform: translateY(-50%);
    fill: none;
    stroke: var(--color-text-secondary);
    stroke-width: 1.6;
    stroke-linecap: round;
    pointer-events: none;
  }
  .search-box input {
    width: 132px;
    height: 36px;
    padding: 0 var(--space-3) 0 34px;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    transition:
      width var(--motion-base) ease-out,
      background var(--motion-fast);
  }
  .search-box input:hover {
    background: rgb(255 255 255 / 0.08);
  }
  .search-box input:focus,
  .search-box input:not(:placeholder-shown) {
    width: 220px;
    background: rgb(0 0 0 / 0.35);
  }
  .search-box input::placeholder {
    color: var(--color-text-secondary);
  }
  /* Narrow window: the search shrinks to its icon so the whole pill fits, and
     covers the pill while it has the focus. */
  @media (max-width: 900px) {
    .pill {
      position: relative;
    }
    .search-box input,
    .search-box input:not(:placeholder-shown) {
      width: 36px;
      padding-right: 0;
    }
    .search-box input:not(:focus)::placeholder {
      color: transparent;
    }
    .search-box input:not(:focus):not(:placeholder-shown) {
      color: transparent;
      background: rgb(143 211 245 / 0.22);
    }
    .search-box:focus-within {
      position: absolute;
      inset: 6px;
      z-index: 1;
    }
    .search-box input:focus {
      width: 100%;
      padding-right: var(--space-3);
      background: var(--color-elevated);
    }
    .libraries button,
    .home-link {
      padding: 0 12px;
    }
  }
  .search-box input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 0;
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
  .nav-note {
    padding: 0 var(--space-4);
    color: var(--color-text-secondary);
    white-space: nowrap;
  }
  /* The only part of the pill that scrolls. The 4 px padding, taken back by the
     margin, keeps the focus ring inside the scroll box. */
  .libraries {
    list-style: none;
    min-width: 0;
    margin: -4px;
    padding: 4px;
    display: flex;
    gap: var(--space-1);
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
    scroll-padding-inline: 40px;
  }
  .libraries.fade-end {
    mask-image: linear-gradient(to right, #000 calc(100% - 40px), transparent);
  }
  .libraries.fade-start {
    mask-image: linear-gradient(to left, #000 calc(100% - 40px), transparent);
  }
  .libraries.fade-start.fade-end {
    mask-image: linear-gradient(
      to right,
      transparent,
      #000 40px,
      #000 calc(100% - 40px),
      transparent
    );
  }
  .libraries button,
  .home-link {
    height: 36px;
    padding: 0 20px;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    font-weight: 500;
    white-space: nowrap;
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  .libraries button:hover,
  .home-link:hover,
  .icon:hover {
    background: rgb(255 255 255 / 0.12);
  }
  .libraries button[aria-current="page"],
  .home-link[aria-current="page"],
  .icon[aria-current="page"] {
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-weight: 600;
  }
  .icon {
    flex: 0 0 auto;
    width: 44px;
    height: 36px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    cursor: pointer;
  }
  .icon svg {
    width: 18px;
    height: 18px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .avatar {
    anchor-name: --account;
  }
  .avatar span {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    border-radius: 50%;
    background: linear-gradient(160deg, #8fd3f5, #3d9bd0);
    color: #04121b;
    font-size: 13px;
    font-weight: 700;
  }
  /* Top layer: light dismiss and Escape come with the popover. */
  .account-menu {
    /* Child of .topnav, which lets clicks through. */
    pointer-events: auto;
    position-anchor: --account;
    inset: auto;
    top: anchor(bottom);
    right: anchor(right);
    margin: var(--space-2) 0 0;
    min-width: 240px;
    padding: var(--space-2);
    border: 1px solid var(--separator);
    border-radius: var(--radius-md);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    box-shadow: 0 24px 48px rgb(0 0 0 / 0.55);
  }
  .account-menu:popover-open {
    display: grid;
    gap: 2px;
  }
  .who {
    margin: 0 0 var(--space-1);
    padding: var(--space-2) var(--space-3) var(--space-3);
    border-bottom: 1px solid var(--separator);
    font-weight: 600;
    display: grid;
  }
  .who span {
    font-weight: 400;
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .menu-item {
    text-align: left;
    padding: var(--space-2) var(--space-3);
    border: 0;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    cursor: pointer;
  }
  .menu-item:hover:not(:disabled) {
    background: rgb(255 255 255 / 0.1);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button.quiet {
    background: transparent;
    color: var(--color-text-secondary);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    font: inherit;
    cursor: pointer;
  }
  button.quiet:hover:not(:disabled) {
    background: rgb(255 255 255 / 0.1);
    color: var(--color-text);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  .pane {
    position: relative;
    height: 100%;
    min-height: 0;
    min-width: 0;
  }
  .content {
    height: 100%;
    min-height: 0;
    overflow-y: auto;
    /* Clears the navigation pill floating above. */
    --gutter: clamp(var(--space-4), 5vw, 80px);
    padding: 76px var(--gutter) 48px;
  }
  .heading {
    display: flex;
    align-items: baseline;
    gap: 14px;
    margin-bottom: 20px;
  }
  h1 {
    margin: 0;
    font-size: clamp(32px, 3vw, 44px);
    font-weight: 700;
    line-height: 1.05;
  }
  .count {
    margin: 0;
    font-size: 15px;
    color: var(--color-text-secondary);
  }
  .toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }
  .field {
    display: grid;
    gap: 6px;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  /* Glass controls; the native list keeps the keyboard and screen readers. */
  .field select {
    appearance: none;
    min-width: 140px;
    max-width: 220px;
    height: 38px;
    padding: 0 36px 0 14px;
    border: 0;
    border-radius: var(--radius-sm);
    background:
      url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23f5f5f7' stroke-width='2.5' stroke-linecap='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E")
        no-repeat right 14px center,
      var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: background-color var(--motion-fast);
  }
  .field select:hover {
    background-color: var(--fill-glass-hover);
  }
  .field select option {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .field select:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    height: 38px;
    padding: 0 14px;
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  .toggle svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.4;
    stroke-linejoin: round;
  }
  .toggle:hover {
    background: var(--fill-glass-hover);
  }
  .toggle[aria-pressed="true"] {
    background: var(--color-primary);
    color: var(--color-on-primary);
  }
  .toggle[aria-pressed="true"] svg {
    fill: currentColor;
  }
  .toggle:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button.reset {
    height: 38px;
    font-size: 14px;
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 30px 24px;
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
    background: var(--color-primary);
    color: var(--color-on-primary);
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
</style>
