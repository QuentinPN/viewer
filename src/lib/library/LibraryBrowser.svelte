<script lang="ts">
// Signed-in shell: the sidebar with the search field, then the home page
// (ADR 0010), the paged poster grid of a library (DESIGN.md, Films/Series) or the
// search results (ADR 0011), and the detail page of the opened item over them.
import { onMount, tick } from "svelte";
import ItemDetail from "../detail/ItemDetail.svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  SearchResults as Found,
  Library,
  MediaItem,
  PlaybackStart,
  SignedInUser,
} from "../emby/types";
import Home from "../home/Home.svelte";
import {
  cleanQuery,
  isSearchable,
  SEARCH_DEBOUNCE_MS,
  SEARCH_MAX_CHARS,
} from "../search/query";
import SearchResults from "../search/SearchResults.svelte";
import PosterCard from "./PosterCard.svelte";
import { appendPage, countLabel, emptyPager, PAGE_SIZE } from "./pager";

interface Props {
  user: SignedInUser;
  signingOut: boolean;
  onSignOut: () => void;
  onOpenPlayer: () => void;
  /** The token was refused while browsing: back to sign-in. */
  onSessionExpired: (error: EmbyError) => void;
  /** A film or episode started playing: show the player over this view. */
  onPlay: (start: PlaybackStart) => void;
}

let {
  user,
  signingOut,
  onSignOut,
  onOpenPlayer,
  onSessionExpired,
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
// Bumped on every library switch: answers for a previous library are dropped.
let generation = 0;
let observer: IntersectionObserver | null = null;
let opened = $state<MediaItem | null>(null);

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

function fail(e: unknown): EmbyError {
  const error = toEmbyError(e);
  if (error.kind === "sessionExpired") onSessionExpired(error);
  return error;
}

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
}

/** Back to the grid, focus on the card that opened the detail. */
async function closeDetail() {
  const id = opened?.id;
  opened = null;
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

function showHome() {
  opened = null;
  if (searching) void leaveSearch(false);
  if (selected === null) return;
  generation += 1;
  selected = null;
  pager = emptyPager();
  pageError = null;
  loading = false;
  scroller?.scrollTo({ top: 0 });
}

function select(library: Library) {
  opened = null;
  if (searching) void leaveSearch(false);
  if (selected?.id === library.id && pager.total !== null) return;
  generation += 1;
  selected = library;
  pager = emptyPager();
  pageError = null;
  loading = false;
  scroller?.scrollTo({ top: 0 });
  void loadMore();
}

async function loadMore() {
  const library = selected;
  if (!library || loading || pager.done) return;
  const gen = generation;
  loading = true;
  pageError = null;
  try {
    const page = await embyApi.items(library.id, pager.next, PAGE_SIZE);
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
      <label class="sr-only" for="search-field">Rechercher un film ou une série</label>
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
        aria-current={selected === null && !searching ? "page" : undefined}
        onclick={showHome}
      >
        Accueil
      </button>
      <ul class="libraries">
        {#each libraries as library (library.id)}
          <li>
            <button
              type="button"
              aria-current={selected?.id === library.id && !searching
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
      <button type="button" class="quiet" onclick={onOpenPlayer}>
        Prototype de lecture
      </button>
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
      />
    {:else if libraries && selected === null}
      <Home
        {libraries}
        userName={user.userName}
        onOpen={open}
        onShowLibrary={select}
        {onPlay}
        onError={fail}
      />
    {:else if selected}
      <header class="heading">
        <h1>{selected.name}</h1>
        {#if pager.total !== null}
          <p class="count">{countLabel(pager.total, selected.kind)}</p>
        {/if}
      </header>

      {#if pager.total === 0}
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
      <ItemDetail item={opened} onClose={closeDetail} onError={fail} {onPlay} />
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
