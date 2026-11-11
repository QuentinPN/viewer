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
  withSort,
} from "./options";
import PosterCard from "./PosterCard.svelte";
import { appendPage, countLabel, emptyPager, PAGE_SIZE } from "./pager";
import {
  canReset,
  counterLabel,
  decadeChoice,
  decadesOf,
  edgeLetter,
  favoritesLabel,
  filtersActive,
  filtersLabel,
  firstVisible,
  jumpLimit,
  LETTERS,
  type LetterStart,
  letterIndex,
  letterLabel,
  nextWatch,
  sortActive,
  sortLabel,
  stepLetter,
  watchLabel,
} from "./rails";

/** Largest page Rust accepts (`MAX_PAGE`): a letter jump loads by this much. */
const MAX_PAGE = 200;

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
const directions = $derived(directionLabels(options.sort));

// Compact grid (ADR 0101): letter rail on the left, icon rail on the right.
let sortButton = $state<HTMLButtonElement | null>(null);
let filtersButton = $state<HTMLButtonElement | null>(null);
let randomButton = $state<HTMLButtonElement | null>(null);
let gridList = $state<HTMLElement | null>(null);
/** Letter index of the current options; `null` while loading or failed. */
let letters = $state<Map<string, LetterStart> | null>(null);
let lettersError = $state(false);
let lettersGeneration = 0;
/** Letter the roving focus of the rail sits on, then the last one chosen. */
let railLetter = $state<string | null>(null);
let chosenLetter = $state<string | null>(null);
/** Letters that can be reached; `null` when the index failed (all of them). */
const reachable = $derived<ReadonlySet<string> | null>(
  lettersError ? null : new Set(letters?.keys() ?? []),
);
const lettersShown = $derived(options.sort === "name");
let decades = $state<number[] | null>(null);
let decadesError = $state(false);
let decadesGeneration = 0;
let randomBusy = $state(false);
/** Short note beside the count (empty random pick, failed jump or pick). */
let notice = $state<string | null>(null);
/** Where the focus goes when the detail page closes, when not a card. */
let focusAfterDetail: HTMLElement | null = null;
let focusedIndex = $state<number | null>(null);
let visibleIndex = $state<number | null>(null);
const counter = $derived(
  counterLabel(
    focusedIndex ?? visibleIndex ?? (pager.items.length > 0 ? 0 : null),
    pager.total,
  ),
);
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
  if (lettersError) void loadLetters();
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
  focusAfterDetail = null;
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

/** Back to the grid, focus on the card (or the icon) that opened the detail. */
async function closeDetail() {
  const id = openerId;
  const back = focusAfterDetail;
  opened = null;
  openerId = null;
  focusAfterDetail = null;
  await tick();
  if (back?.isConnected) {
    back.focus({ preventScroll: true });
    return;
  }
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
  decadesGeneration += 1;
  lettersGeneration += 1;
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
  inflight = null;
  notice = null;
  focusedIndex = null;
  visibleIndex = null;
  chosenLetter = null;
  railLetter = null;
  scroller?.scrollTo({ top: 0 });
  void loadMore();
  void loadLetters();
}

function select(library: Library) {
  opened = null;
  settingsOpen = false;
  if (searching) void leaveSearch(false);
  if (selected?.id === library.id && pager.total !== null) return;
  selected = library;
  options = loadOptions(storage(), optionsKey(library), library.kind);
  decades = null;
  decadesError = false;
  decadesGeneration += 1;
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

/** The reset icon disappears with the change: focus goes to Tri. */
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
  sortButton?.focus();
}

/**
 * Letter index of the current options (ADR 0101), read when the grid sorts
 * by title: one light request per library or option change, no image.
 */
async function loadLetters() {
  const library = selected;
  const gen = ++lettersGeneration;
  letters = null;
  lettersError = false;
  if (!library || options.sort !== "name") return;
  try {
    const entries = await embyApi.sortNames(
      library.id,
      $state.snapshot(options),
    );
    if (gen !== lettersGeneration) return;
    letters = letterIndex(entries);
  } catch (e) {
    if (gen !== lettersGeneration) return;
    lettersError = true;
    fail(e);
  }
}

/** Loads the grid up to the letter's first title, shows and focuses it. */
async function jumpToLetter(letter: string) {
  railLetter = letter;
  if (lettersError) {
    // The index failed: every letter stays reachable and reads it again.
    await loadLetters();
    if (!letters) return;
  }
  const start = letters?.get(letter);
  if (!start) return;
  chosenLetter = letter;
  notice = null;
  const gen = generation;
  const has = () => pager.items.some((i) => i.id === start.id);
  while (gen === generation && !has() && !pager.done && !pageError) {
    const limit = jumpLimit(start.position, pager.next, PAGE_SIZE, MAX_PAGE);
    if (limit === 0) break;
    await loadMore(limit);
  }
  if (gen !== generation) return;
  await tick();
  const card =
    gridList?.querySelector<HTMLElement>(
      `[data-item-id="${CSS.escape(start.id)}"]`,
    ) ??
    // An item Rust dropped as unusable: its neighbour in the server's order.
    gridList?.querySelectorAll<HTMLElement>("[data-item-id]")[
      Math.min(start.position, pager.items.length - 1)
    ];
  if (!card) return;
  card.scrollIntoView({ block: "center", behavior: "instant" });
  card.focus({ preventScroll: true });
}

/** Up/Down walk the letters that have a title; Home/End go to the ends. */
function onLetterKeydown(e: KeyboardEvent) {
  const from = (e.target as HTMLElement).dataset.letter ?? tabLetter;
  if (!from) return;
  let to: string | null = null;
  if (e.key === "ArrowDown") to = stepLetter(from, 1, reachable);
  else if (e.key === "ArrowUp") to = stepLetter(from, -1, reachable);
  else if (e.key === "Home") to = edgeLetter(1, reachable);
  else if (e.key === "End") to = edgeLetter(-1, reachable);
  if (to === null) return;
  e.preventDefault();
  railLetter = to;
  (e.currentTarget as HTMLElement)
    .querySelector<HTMLElement>(`[data-letter="${CSS.escape(to)}"]`)
    ?.focus();
}

/** Letter holding the rail's single tab stop. */
const tabLetter = $derived(
  railLetter !== null && (!reachable || reachable.has(railLetter))
    ? railLetter
    : edgeLetter(1, reachable),
);

/** Up/Down move between the round icons, like the letters. */
function onIconsKeydown(e: KeyboardEvent) {
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
  const buttons = [
    ...(e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>(
      "button.rail-icon",
    ),
  ];
  const i = buttons.indexOf(document.activeElement as HTMLElement);
  if (i < 0) return;
  e.preventDefault();
  const next = buttons[i + (e.key === "ArrowDown" ? 1 : -1)];
  next?.focus();
}

/** Decades of the library, read once on the first opening of Filtres. */
async function loadDecades() {
  const library = selected;
  if (!library || decades !== null) return;
  const gen = ++decadesGeneration;
  decadesError = false;
  try {
    const years = await embyApi.productionYears(library.id);
    if (gen !== decadesGeneration) return;
    decades = decadesOf(years);
  } catch (e) {
    if (gen !== decadesGeneration) return;
    decadesError = true;
    fail(e);
  }
}

/** Opens a title drawn by the server among those the grid shows. */
async function pickRandom() {
  const library = selected;
  if (!library || randomBusy) return;
  const gen = generation;
  randomBusy = true;
  notice = null;
  try {
    const item = await embyApi.randomItem(library.id, $state.snapshot(options));
    if (gen !== generation) return;
    if (!item) {
      notice = isFiltered(options)
        ? "Aucun titre avec ces filtres."
        : "Cette bibliothèque est vide.";
      return;
    }
    open(item);
    focusAfterDetail = randomButton;
  } catch (e) {
    if (gen !== generation) return;
    notice = fail(e).message;
  } finally {
    randomBusy = false;
  }
}

/**
 * Focus goes into a panel on its current choice; when it closes with the focus
 * inside (Échap, or the panel's choice), it goes back to its icon.
 */
function onPanelToggle(e: Event, invoker: HTMLElement | null) {
  const panel = e.currentTarget as HTMLElement;
  if ((e as ToggleEvent).newState === "open") {
    void tick().then(() =>
      panel.querySelector<HTMLElement>("input:checked, select")?.focus(),
    );
    return;
  }
  const active = document.activeElement;
  if (!active || active === document.body || panel.contains(active)) {
    invoker?.focus({ preventScroll: true });
  }
}

/** The counter follows the focused card, else the first one in view. */
function onGridFocusIn(e: FocusEvent) {
  const li = (e.target as HTMLElement).closest<HTMLElement>("li[data-index]");
  focusedIndex = li ? Number(li.dataset.index) : null;
}

function onGridFocusOut(e: FocusEvent) {
  const to = e.relatedTarget as Node | null;
  if (!to || !gridList?.contains(to)) focusedIndex = null;
}

let scrollFrame = 0;
function onContentScroll() {
  if (scrollFrame || !selected) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    const list = gridList;
    if (!list || !scroller) return;
    const cards = list.querySelectorAll<HTMLElement>("li[data-index]");
    const top = scroller.getBoundingClientRect().top + 76;
    visibleIndex = firstVisible(
      cards.length,
      (i) => {
        const r = (cards[i] as HTMLElement).getBoundingClientRect();
        return { top: r.top, height: r.height };
      },
      top,
    );
  });
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

/** The page being read: a letter jump waits for it before asking for more. */
let inflight: Promise<void> | null = null;

function loadMore(limit: number = PAGE_SIZE): Promise<void> {
  if (loading && inflight) return inflight;
  const read: Promise<void> = readPage(limit).finally(() => {
    if (inflight === read) inflight = null;
  });
  inflight = read;
  return read;
}

async function readPage(limit: number) {
  const library = selected;
  if (!library || loading || pager.done) return;
  const gen = generation;
  loading = true;
  pageError = null;
  try {
    const page = await embyApi.items(
      library.id,
      pager.next,
      limit,
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
    cancelAnimationFrame(scrollFrame);
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
    onscroll={onContentScroll}
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
        {#if notice}<p class="notice" role="status">{notice}</p>{/if}
      </header>

      <div class="library-body">
        <div class="letter-col">
          {#if lettersShown}
            <div
              class="letter-rail"
              role="toolbar"
              aria-orientation="vertical"
              aria-label="Aller à une lettre"
              aria-busy={letters === null && !lettersError}
              tabindex="-1"
              onkeydown={onLetterKeydown}
            >
              {#each LETTERS as letter (letter)}
                {@const available = !reachable || reachable.has(letter)}
                <button
                  type="button"
                  class="letter"
                  class:chosen={chosenLetter === letter}
                  data-letter={letter}
                  tabindex={letter === tabLetter ? 0 : -1}
                  disabled={!available}
                  aria-label={letterLabel(letter, available)}
                  onclick={() => void jumpToLetter(letter)}
                  onfocus={() => (railLetter = letter)}
                >
                  {letter}
                </button>
              {/each}
            </div>
          {/if}
        </div>

        <div class="grid-col">
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
            <ul
              class="grid"
              aria-label={selected.name}
              bind:this={gridList}
              onfocusin={onGridFocusIn}
              onfocusout={onGridFocusOut}
            >
              {#each pager.items as item, i (item.id)}
                <li data-index={i}><PosterCard {item} onOpen={open} /></li>
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
              <button type="button" onclick={() => loadMore()}>Réessayer</button>
            </div>
          {:else if !pager.done && !loading && pager.total !== null}
            <div class="more">
              <button type="button" class="quiet" onclick={() => loadMore()}>
                Afficher la suite
              </button>
            </div>
          {/if}
        </div>

        <div class="icon-col">
          <div
            class="icon-rail"
            role="toolbar"
            aria-orientation="vertical"
            aria-label="Tri et filtres"
            tabindex="-1"
            onkeydown={onIconsKeydown}
          >
            <button
              type="button"
              class="rail-icon"
              class:active={sortActive(options)}
              style="anchor-name: --sort-icon"
              popovertarget="sort-panel"
              aria-label={sortLabel(options)}
              bind:this={sortButton}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"
                ><path d="M7 4v16M3 16l4 4 4-4M17 20V4M13 8l4-4 4 4" /></svg
              >
              <span class="tip" aria-hidden="true">{sortLabel(options)}</span>
            </button>
            <button
              type="button"
              class="rail-icon"
              class:active={options.watch !== "all"}
              aria-label={watchLabel(selected.kind, options.watch)}
              onclick={() =>
                selected &&
                setOptions({
                  ...options,
                  watch: nextWatch(selected.kind, options.watch),
                })}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"
                ><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" /><circle
                  cx="12"
                  cy="12"
                  r="3"
                /></svg
              >
              <span class="tip" aria-hidden="true"
                >{watchLabel(selected.kind, options.watch)}</span
              >
            </button>
            <button
              type="button"
              class="rail-icon"
              class:active={options.favorites}
              aria-pressed={options.favorites}
              aria-label="Favoris seulement"
              onclick={() => setOptions({ ...options, favorites: !options.favorites })}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true" class:filled={options.favorites}
                ><path
                  d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3l-5.6 2.9 1.1-6.2L3 9.6l6.2-.9z"
                /></svg
              >
              <span class="tip" aria-hidden="true">{favoritesLabel(options.favorites)}</span>
            </button>
            <button
              type="button"
              class="rail-icon"
              class:active={filtersActive(options)}
              style="anchor-name: --filters-icon"
              popovertarget="filters-panel"
              aria-label={filtersLabel(options)}
              bind:this={filtersButton}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"
                ><path d="M3 5h18l-7 8.5V19l-4 2v-7.5z" /></svg
              >
              <span class="tip" aria-hidden="true">{filtersLabel(options)}</span>
            </button>
            <button
              type="button"
              class="rail-icon"
              aria-label="Ouvrir un titre au hasard"
              aria-busy={randomBusy}
              bind:this={randomButton}
              onclick={() => void pickRandom()}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"
                ><path d="M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5" /></svg
              >
              <span class="tip" aria-hidden="true">Aléatoire</span>
            </button>
            {#if canReset(options)}
              <button
                type="button"
                class="rail-icon"
                aria-label="Réinitialiser le tri et les filtres"
                onclick={() => resetOptions(false)}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true"
                  ><path d="M3 12a9 9 0 1 0 2.6-6.4L3 8M3 3v5h5" /></svg
                >
                <span class="tip" aria-hidden="true">Réinitialiser</span>
              </button>
            {:else}
              <!-- Keeps the place of Réinitialiser: the other icons never move. -->
              <span class="rail-slot" aria-hidden="true"></span>
            {/if}
          </div>

          <div
            id="sort-panel"
            class="rail-panel"
            style="position-anchor: --sort-icon"
            role="dialog"
            aria-label="Tri"
            popover
            ontoggle={(e) => onPanelToggle(e, sortButton)}
          >
            <fieldset>
              <legend>Trier par</legend>
              {#each SORTS as s (s.key)}
                <label class="choice">
                  <input
                    type="radio"
                    name="grid-sort"
                    value={s.key}
                    checked={options.sort === s.key}
                    onchange={() => setOptions(withSort(options, s.key as SortKey))}
                  />
                  {s.label}
                </label>
              {/each}
            </fieldset>
            <fieldset>
              <legend>Ordre</legend>
              <label class="choice">
                <input
                  type="radio"
                  name="grid-order"
                  checked={!options.descending}
                  onchange={() => setOptions({ ...options, descending: false })}
                />
                {directions.ascending}
              </label>
              <label class="choice">
                <input
                  type="radio"
                  name="grid-order"
                  checked={options.descending}
                  onchange={() => setOptions({ ...options, descending: true })}
                />
                {directions.descending}
              </label>
            </fieldset>
          </div>

          <div
            id="filters-panel"
            class="rail-panel"
            style="position-anchor: --filters-icon"
            role="dialog"
            aria-label="Filtres"
            popover
            ontoggle={(e) => {
              if ((e as ToggleEvent).newState === "open") void loadDecades();
              onPanelToggle(e, filtersButton);
            }}
          >
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
            <label class="field">
              <span>Années</span>
              <select
                value={options.decade === null ? "" : String(options.decade)}
                aria-busy={decades === null && !decadesError}
                onchange={(e) =>
                  setOptions({
                    ...options,
                    decade: e.currentTarget.value
                      ? Number(e.currentTarget.value)
                      : null,
                  })}
              >
                <option value="">
                  {decadesError ? "Années indisponibles" : "Toutes les années"}
                </option>
                {#if options.decade !== null && !decades?.includes(options.decade)}
                  <option value={String(options.decade)}
                    >{decadeChoice(options.decade)}</option
                  >
                {/if}
                {#each decades ?? [] as d (d)}
                  <option value={String(d)}>{decadeChoice(d)}</option>
                {/each}
              </select>
            </label>
          </div>
        </div>
      </div>
    {/if}
    <div class="sentinel" bind:this={sentinel} aria-hidden="true"></div>
  </main>
  {#if selected && counter && !searching && !settingsOpen && !opened}
    <p class="counter" aria-hidden="true">{counter}</p>
  {/if}
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
  /* Compact grid (ADR 0101): small header, letter rail, grid, icon rail. */
  .heading {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    column-gap: var(--space-3);
    row-gap: var(--space-1);
    margin: 0 0 var(--space-4) calc(var(--letter-col) + var(--rail-gap));
  }
  h1 {
    margin: 0;
    font-size: 22px;
    font-weight: 700;
    line-height: 1.2;
  }
  .count,
  .notice {
    margin: 0;
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .notice {
    color: var(--color-text);
  }
  .content {
    --letter-col: 22px;
    --icon-col: 40px;
    --rail-gap: 16px;
    /* Room under the floating pill, where the rails stick. */
    --rails-top: 76px;
  }
  .library-body {
    display: grid;
    grid-template-columns: var(--letter-col) minmax(0, 1fr) var(--icon-col);
    column-gap: var(--rail-gap);
    align-items: start;
  }
  .letter-col,
  .icon-col {
    position: sticky;
    top: var(--rails-top);
    align-self: start;
  }
  /* About 20 px per letter on a 900 px window; a very short window scrolls
     the rail rather than overlapping its letters. */
  .letter-rail {
    display: flex;
    flex-direction: column;
    height: calc(100vh - var(--titlebar-height) - var(--rails-top) - 48px);
    max-height: 640px;
    overflow-y: auto;
    scrollbar-width: none;
    padding: 2px 0;
  }
  .letter {
    flex: 1 1 0;
    min-height: 15px;
    width: var(--letter-col);
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 11px;
    font-weight: 600;
    line-height: 1;
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  .letter:hover:not(:disabled) {
    background: rgb(255 255 255 / 0.12);
    color: var(--color-text);
  }
  .letter.chosen {
    color: var(--color-accent);
  }
  .letter:disabled {
    opacity: 0.3;
    cursor: default;
  }
  .letter:focus-visible {
    outline-offset: 0;
  }
  .icon-rail {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .rail-icon,
  .rail-slot {
    width: var(--icon-col);
    height: var(--icon-col);
    flex: 0 0 auto;
  }
  .rail-icon {
    position: relative;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: var(--fill-glass);
    color: var(--color-text);
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  .rail-icon:hover {
    background: var(--fill-glass-hover);
  }
  .rail-icon.active {
    color: var(--color-accent);
  }
  .rail-icon svg {
    width: 20px;
    height: 20px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .rail-icon svg.filled {
    fill: currentColor;
  }
  .rail-icon[aria-busy="true"] {
    opacity: 0.6;
    cursor: progress;
  }
  /* Label on the left of the icon, over the grid, on hover and keyboard focus. */
  .tip {
    position: absolute;
    right: calc(100% + 10px);
    top: 50%;
    transform: translateY(-50%);
    padding: 6px 10px;
    border-radius: var(--radius-sm);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    border: 1px solid var(--separator);
    color: var(--color-text);
    font-size: 13px;
    font-weight: 500;
    white-space: nowrap;
    pointer-events: none;
    opacity: 0;
    transition: opacity var(--motion-fast);
  }
  .rail-icon:hover .tip,
  .rail-icon:focus-visible .tip {
    opacity: 1;
  }
  /* Top layer, anchored on the left of its icon; flipped up near the bottom. */
  .rail-panel {
    inset: auto;
    top: anchor(top);
    right: anchor(left);
    margin: 0 12px 0 0;
    position-try-fallbacks: flip-block;
    min-width: 220px;
    max-height: calc(100vh - var(--titlebar-height) - 24px);
    overflow-y: auto;
    padding: var(--space-3);
    border: 1px solid var(--separator);
    border-radius: var(--radius-md);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    box-shadow: 0 24px 48px rgb(0 0 0 / 0.55);
  }
  .rail-panel:popover-open {
    display: grid;
    gap: var(--space-3);
  }
  .rail-panel fieldset {
    margin: 0;
    padding: 0;
    border: 0;
    display: grid;
    gap: 2px;
  }
  .rail-panel legend {
    padding: 0 var(--space-2) var(--space-1);
    font-size: 12px;
    color: var(--color-text-muted);
  }
  .choice {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 6px var(--space-2);
    border-radius: var(--radius-sm);
    font-size: 14px;
    cursor: pointer;
  }
  .choice:hover {
    background: rgb(255 255 255 / 0.1);
  }
  .choice input {
    accent-color: var(--color-accent);
    margin: 0;
  }
  .choice input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
    min-width: 200px;
    max-width: 260px;
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
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    /* About 7 posters on a 1600 px window, 3 at the 640 px minimum. */
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 24px 18px;
  }
  .counter {
    position: absolute;
    right: var(--space-4);
    bottom: var(--space-3);
    z-index: 2;
    margin: 0;
    padding: 4px 10px;
    border-radius: var(--radius-pill);
    background: var(--material);
    backdrop-filter: var(--material-blur);
    color: var(--color-text-secondary);
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }
  /* Short or narrow windows: smaller rails, the letters stay reachable. */
  @media (max-width: 720px) {
    .content {
      --icon-col: 34px;
      --rail-gap: 10px;
    }
    .rail-icon svg {
      width: 17px;
      height: 17px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .letter,
    .rail-icon,
    .tip {
      transition: none;
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
