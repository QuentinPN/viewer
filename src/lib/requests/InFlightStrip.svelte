<script lang="ts">
// « Mes demandes en cours » above the Découvrir rows (strip C): at most three
// requests, hidden when there is none.
import { onMount } from "svelte";
import "./requests.css";
import { requestsApi } from "./api";
import { inFlight } from "./browse";
import { percent, requestStatusLabel, statusTone } from "./labels";
import type { MediaSummary, RequestView } from "./types";

interface Props {
  onOpen: (media: MediaSummary) => void;
}

let { onOpen }: Props = $props();

let shown = $state<RequestView[]>([]);
/** Posters that failed to load, by request id. */
let failed = $state<Set<number>>(new Set());

onMount(() => {
  requestsApi.myRequests(null, 1).then(
    (page) => (shown = inFlight(page.results, Date.now())),
    // Only a shortcut: the rows below stay usable without it.
    () => {},
  );
});

function line(r: RequestView): string {
  const p =
    r.media?.status === "downloading" ? percent(r.media.progress) : null;
  return p
    ? `${requestStatusLabel(r.status)} · ${p}`
    : requestStatusLabel(r.status);
}
</script>

{#if shown.length > 0}
  <section class="strip" aria-labelledby="ur-inflight">
    <h2 id="ur-inflight">Mes demandes en cours</h2>
    <ul>
      {#each shown as r (r.id)}
        {@const m = r.media}
        <li>
          <button
            type="button"
            class="card"
            disabled={!m}
            onclick={() => m && onOpen(m)}
          >
            <span class="thumb">
              {#if m?.poster && !failed.has(r.id)}
                <img
                  src={m.poster.src}
                  srcset={m.poster.srcset}
                  sizes="48px"
                  alt=""
                  loading="lazy"
                  decoding="async"
                  onerror={() => (failed = new Set([...failed, r.id]))}
                />
              {/if}
            </span>
            <span class="text">
              <span class="title">{m?.title ?? `Demande n° ${r.id}`}</span>
              <span class="status ur-tone-{statusTone(r.status)}">{line(r)}</span>
              {#if m?.progress !== null && m?.progress !== undefined}
                <span class="bar" aria-hidden="true"
                  ><span style="width: {Math.min(m.progress, 1) * 100}%"></span></span
                >
              {/if}
            </span>
          </button>
        </li>
      {/each}
    </ul>
  </section>
{/if}

<style>
  .strip {
    margin-bottom: var(--space-3);
  }
  h2 {
    margin: 0 0 var(--space-2);
    font-size: 17px;
    font-weight: 600;
    color: var(--color-text-secondary);
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: var(--space-3);
  }
  .card {
    width: 100%;
    display: flex;
    gap: var(--space-3);
    align-items: center;
    padding: var(--space-2);
    border: 1px solid var(--separator);
    border-radius: var(--radius-md);
    background: var(--color-surface);
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .card:hover:not(:disabled) {
    background: var(--color-elevated);
  }
  .card:disabled {
    cursor: default;
  }
  .card:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .thumb {
    flex: 0 0 auto;
    width: 48px;
    aspect-ratio: 2 / 3;
    border-radius: 6px;
    overflow: hidden;
    background: var(--color-elevated);
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .text {
    min-width: 0;
    flex: 1;
    display: grid;
    gap: 4px;
  }
  .title {
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .status {
    font-size: 13px;
  }
  .bar {
    height: 3px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.18);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: var(--color-primary);
  }
</style>
