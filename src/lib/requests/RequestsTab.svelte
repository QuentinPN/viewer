<script lang="ts">
// The « Demander » tab: its state first, then Découvrir, Films,
// Séries and Mes demandes once Request is ready, with a search field; the
// setup in place otherwise.
import { onMount, tick } from "svelte";
import type { MediaItem } from "../emby/types";
import { cleanQuery } from "../search/query";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import Browse from "./Browse.svelte";
import { DEFAULT_FILTERS, filtersFromSeeAll } from "./browse";
import Discover from "./Discover.svelte";
import MyRequests from "./MyRequests.svelte";
import { openCount } from "./myRequests";
import RequestsSetup from "./RequestsSetup.svelte";
import SearchList from "./SearchList.svelte";
import type {
  MediaSummary,
  MediaType,
  RequestsError,
  RequestTotals,
  SeeAll,
  SeeAllFilters,
  UrStatus,
  Viewer,
} from "./types";

interface Props {
  /** A title opens its page over the tab. */
  onOpenMedia: (media: MediaSummary, viewer: Viewer | null) => void;
  /** « Lire »: the Emby title in the existing detail page. */
  onPlay: (item: MediaItem) => void;
}

let { onOpenMedia, onPlay }: Props = $props();

type View =
  | { kind: "discover" }
  | {
      kind: "browse";
      type: MediaType;
      filters: SeeAllFilters;
      from: string | null;
    }
  | { kind: "mine" };

let status = $state<UrStatus | null>(null);
let error = $state<RequestsError | null>(null);
let view = $state<View>({ kind: "discover" });
/** Remounts Parcourir when a « Tout voir » or a segment starts it over. */
let browseKey = $state(0);
let heading = $state<HTMLElement | null>(null);
/** « Mes demandes · N »: waiting, approved or sent; unknown until read. */
let open = $state<number | null>(null);
let query = $state("");
const cleaned = $derived(cleanQuery(query));

const SEGMENTS: { label: string; view: () => View }[] = [
  { label: "Découvrir", view: () => ({ kind: "discover" }) },
  {
    label: "Films",
    view: () => ({
      kind: "browse",
      type: "movie",
      filters: DEFAULT_FILTERS,
      from: null,
    }),
  },
  {
    label: "Séries",
    view: () => ({
      kind: "browse",
      type: "tv",
      filters: DEFAULT_FILTERS,
      from: null,
    }),
  },
  { label: "Mes demandes", view: () => ({ kind: "mine" }) },
];

function isCurrent(label: string): boolean {
  if (cleaned !== "") return false;
  if (label === "Découvrir") return view.kind === "discover";
  if (label === "Mes demandes") return view.kind === "mine";
  if (view.kind !== "browse") return false;
  return (view.type === "movie") === (label === "Films");
}

function show(next: View) {
  query = "";
  view = next;
  browseKey += 1;
}

async function seeAll(s: SeeAll, title: string) {
  show({
    kind: "browse",
    type: s.type,
    filters: filtersFromSeeAll(s),
    from: title,
  });
  await tick();
  heading?.scrollIntoView({ block: "start" });
}

async function load() {
  error = null;
  try {
    status = await requestsApi.status();
  } catch (e) {
    error = toRequestsError(e);
    return;
  }
  if (status.state !== "ready") return;
  requestsApi.myRequests(null, 1).then(
    (page) => setTotals(page.totals),
    // The segment reads « Mes demandes » without its count.
    () => {},
  );
}

function setTotals(totals: RequestTotals) {
  open = openCount(totals);
}

const openMedia = (media: MediaSummary) =>
  onOpenMedia(media, status?.viewer ?? null);

onMount(load);
</script>

<header class="heading" bind:this={heading}>
  <h1>Demander</h1>
  {#if status?.state === "ready"}
    <div class="ur-segments" role="group" aria-label="Sections de Demander">
      {#each SEGMENTS as s (s.label)}
        <button
          type="button"
          aria-current={isCurrent(s.label) ? "page" : undefined}
          onclick={() => show(s.view())}
        >
          {s.label}{#if s.label === "Mes demandes" && open !== null}&nbsp;· {open}{/if}
        </button>
      {/each}
    </div>
    <input
      class="ur-input search"
      type="search"
      placeholder="Rechercher un film ou une série"
      aria-label="Rechercher dans Request"
      bind:value={query}
    />
  {/if}
</header>

{#if error}
  <div class="state" role="alert">
    <p>{error.message}</p>
    <button type="button" class="ur-btn" onclick={load}>Réessayer</button>
  </div>
{:else if status === null}
  <p class="ur-muted" aria-busy="true">Connexion à Request…</p>
{:else if status.state !== "ready"}
  <RequestsSetup {status} onStatus={(s) => (status = s)} />
{:else if cleaned !== ""}
  <SearchList
    query={cleaned}
    viewer={status.viewer}
    onOpen={openMedia}
    {onPlay}
  />
{:else if view.kind === "discover"}
  <Discover onOpen={openMedia} onSeeAll={seeAll} onShowAll={() => show({ kind: "mine" })} />
{:else if view.kind === "mine"}
  <MyRequests {onPlay} onTotals={setTotals} />
{:else}
  {#if view.from}<p class="from ur-muted">{view.from}</p>{/if}
  {#key browseKey}
    <Browse type={view.type} initial={view.filters} onOpen={openMedia} />
  {/key}
{/if}

<style>
  .heading {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3) var(--space-6);
    margin-bottom: var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 34px;
  }
  .search {
    margin-left: auto;
    width: min(320px, 100%);
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  p {
    margin: 0;
  }
  .from {
    margin: calc(-1 * var(--space-3)) 0 var(--space-3);
    font-size: 15px;
  }
</style>
