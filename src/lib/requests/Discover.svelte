<script lang="ts">
// Découvrir (B + strip C): « Mes demandes en cours », the quota, then the rows of
// `GET /api/discover` in the server's order, « Tout voir » opening Parcourir.
import { onMount } from "svelte";
import Row from "../home/Row.svelte";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import { orderedRows } from "./browse";
import InFlightStrip from "./InFlightStrip.svelte";
import { quotaLine } from "./labels";
import MediaCard from "./MediaCard.svelte";
import type { DiscoverRow, MediaSummary, RequestsError, SeeAll } from "./types";

interface Props {
  onOpen: (media: MediaSummary) => void;
  onSeeAll: (seeAll: SeeAll, title: string) => void;
}

let { onOpen, onSeeAll }: Props = $props();

let rows = $state<DiscoverRow[] | null>(null);
let error = $state<RequestsError | null>(null);
let quota = $state<string | null>(null);

async function load() {
  error = null;
  rows = null;
  try {
    rows = orderedRows(await requestsApi.discover());
  } catch (e) {
    error = toRequestsError(e);
  }
}

onMount(() => {
  void load();
  requestsApi.quota().then(
    (q) => (quota = quotaLine(q)),
    () => {},
  );
});
</script>

<InFlightStrip {onOpen} />
{#if quota}<p class="quota ur-muted">{quota}</p>{/if}

{#if error}
  <div class="state" role="alert">
    <p>Impossible de charger Découvrir : {error.message}</p>
    <button type="button" class="ur-btn" onclick={load}>Réessayer</button>
  </div>
{:else if rows === null}
  <div class="loading" aria-busy="true" aria-label="Chargement de Découvrir">
    {#each { length: 7 } as _, i (i)}<div class="ur-skeleton"></div>{/each}
  </div>
{:else if rows.length === 0}
  <p class="ur-muted">Rien à découvrir pour le moment.</p>
{:else}
  {#each rows as row (row.id)}
    <Row title={row.title} id="ur-row-{row.id}">
      {#snippet action()}
        {#if row.seeAll}
          {@const seeAll = row.seeAll}
          <button
            type="button"
            class="ur-link"
            aria-label="Tout voir : {row.title}"
            onclick={() => onSeeAll(seeAll, row.title)}
          >
            Tout voir
          </button>
        {/if}
      {/snippet}
      {#each row.items as media (`${media.type}:${media.tmdbId}`)}
        <li><MediaCard {media} {onOpen} /></li>
      {/each}
    </Row>
  {/each}
{/if}

<style>
  p {
    margin: 0;
  }
  .quota {
    margin-bottom: var(--space-4);
    font-size: 13px;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  .loading {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    grid-template-rows: auto;
    grid-auto-rows: 0;
    overflow: hidden;
    gap: 0 24px;
    padding-top: 40px;
  }
</style>
