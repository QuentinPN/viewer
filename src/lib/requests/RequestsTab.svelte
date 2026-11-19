<script lang="ts">
// The « Demander » tab (ADR 0125): its state first, then Découvrir, Films and
// Séries once Request is ready; the setup in place otherwise.
import { onMount, tick } from "svelte";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import Browse from "./Browse.svelte";
import { DEFAULT_FILTERS, filtersFromSeeAll } from "./browse";
import Discover from "./Discover.svelte";
import RequestsSetup from "./RequestsSetup.svelte";
import type {
  MediaSummary,
  MediaType,
  RequestsError,
  SeeAll,
  SeeAllFilters,
  UrStatus,
  Viewer,
} from "./types";

interface Props {
  /** A title opens its page over the tab. */
  onOpenMedia: (media: MediaSummary, viewer: Viewer | null) => void;
}

let { onOpenMedia }: Props = $props();

type View =
  | { kind: "discover" }
  | {
      kind: "browse";
      type: MediaType;
      filters: SeeAllFilters;
      from: string | null;
    };

let status = $state<UrStatus | null>(null);
let error = $state<RequestsError | null>(null);
let view = $state<View>({ kind: "discover" });
/** Remounts Parcourir when a « Tout voir » or a segment starts it over. */
let browseKey = $state(0);
let heading = $state<HTMLElement | null>(null);

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
];

function isCurrent(label: string): boolean {
  if (label === "Découvrir") return view.kind === "discover";
  if (view.kind !== "browse") return false;
  return (view.type === "movie") === (label === "Films");
}

function show(next: View) {
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
  }
}

const open = (media: MediaSummary) =>
  onOpenMedia(media, status?.viewer ?? null);

onMount(load);
</script>

<header class="heading" bind:this={heading}>
  <h1>Demander</h1>
  {#if status?.state === "ready"}
    <div class="segments" role="group" aria-label="Sections de Demander">
      {#each SEGMENTS as s (s.label)}
        <button
          type="button"
          aria-current={isCurrent(s.label) ? "page" : undefined}
          onclick={() => show(s.view())}
        >
          {s.label}
        </button>
      {/each}
    </div>
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
{:else if view.kind === "discover"}
  <Discover onOpen={open} onSeeAll={seeAll} />
{:else}
  {#if view.from}<p class="from ur-muted">{view.from}</p>{/if}
  {#key browseKey}
    <Browse type={view.type} initial={view.filters} onOpen={open} />
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
  .segments {
    display: flex;
    gap: var(--space-1);
    padding: var(--space-1);
    border-radius: var(--radius-pill);
    background: var(--material);
    border: 1px solid var(--separator);
  }
  .segments button {
    height: 32px;
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    font-weight: 500;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .segments button:hover {
    background: rgb(255 255 255 / 0.12);
  }
  .segments button[aria-current="page"] {
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-weight: 600;
  }
  .segments button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
