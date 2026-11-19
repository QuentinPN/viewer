<script lang="ts">
// Mes demandes (A): segments by status with their totals, the paged list, and
// the selected request in a panel on the right with its timeline.
import { onMount } from "svelte";
import type { MediaItem } from "../emby/types";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import {
  eventDate,
  relativeDay,
  requestStatusLabel,
  statusTone,
  typeLabel,
} from "./labels";
import { SEGMENTS, type Segment, segmentCount } from "./myRequests";
import RequestTimeline from "./RequestTimeline.svelte";
import { embyItem } from "./seasons";
import Thumb from "./Thumb.svelte";
import type {
  RequestDetail,
  RequestsError,
  RequestTotals,
  RequestView,
} from "./types";

interface Props {
  /** « Lire »: the Emby title in the existing detail page. */
  onPlay: (item: MediaItem) => void;
  /** New totals, for « Mes demandes · N ». */
  onTotals: (totals: RequestTotals) => void;
}

let { onPlay, onTotals }: Props = $props();

let segment = $state<Segment>(SEGMENTS[0] as Segment);
let items = $state<RequestView[]>([]);
let totals = $state<RequestTotals | null>(null);
let page = $state(0);
let totalPages = $state<number | null>(null);
let loading = $state(false);
let error = $state<RequestsError | null>(null);
let selected = $state<RequestView | null>(null);
let detail = $state<RequestDetail | null>(null);
let detailError = $state<RequestsError | null>(null);
let confirming = $state(false);
let cancelling = $state(false);
let cancelError = $state<RequestsError | null>(null);
let generation = 0;
let detailGeneration = 0;

const now = Date.now();
const more = $derived(totalPages !== null && page < totalPages);

async function loadPage(next: number) {
  const gen = generation;
  loading = true;
  error = null;
  try {
    const answer = await requestsApi.myRequests(segment.statuses, next);
    if (gen !== generation) return;
    items = next === 1 ? answer.results : [...items, ...answer.results];
    page = answer.page;
    totalPages = answer.totalPages;
    totals = answer.totals;
    onTotals(answer.totals);
  } catch (e) {
    if (gen !== generation) return;
    error = toRequestsError(e);
  } finally {
    if (gen === generation) loading = false;
  }
}

function restart() {
  generation += 1;
  items = [];
  page = 0;
  totalPages = null;
  void loadPage(1);
}

function choose(s: Segment) {
  if (s.key === segment.key) return;
  segment = s;
  restart();
}

async function select(r: RequestView) {
  const gen = ++detailGeneration;
  selected = r;
  detail = null;
  detailError = null;
  confirming = false;
  cancelError = null;
  try {
    const d = await requestsApi.requestDetail(r.id);
    if (gen === detailGeneration) detail = d;
  } catch (e) {
    if (gen === detailGeneration) detailError = toRequestsError(e);
  }
}

async function cancel(r: RequestView) {
  cancelling = true;
  cancelError = null;
  try {
    detail = await requestsApi.cancel(r.id);
    selected = detail;
    confirming = false;
    restart();
  } catch (e) {
    cancelError = toRequestsError(e);
  } finally {
    cancelling = false;
  }
}

function meta(r: RequestView): string {
  return `${typeLabel(r.type)} · ${relativeDay(r.createdAt, now)}`;
}

onMount(() => void loadPage(1));
</script>

<div class="ur-segments" role="group" aria-label="Statut des demandes">
  {#each SEGMENTS as s (s.key)}
    <button
      type="button"
      aria-pressed={s.key === segment.key}
      onclick={() => choose(s)}
    >
      {s.label}{#if totals}&nbsp;({segmentCount(totals, s)}){/if}
    </button>
  {/each}
</div>

<div class="layout">
  <div class="list" aria-busy={loading}>
    {#if error}
      <div class="state" role="alert">
        <p>{error.message}</p>
        <button type="button" class="ur-btn" onclick={() => loadPage(page + 1)}>
          Réessayer
        </button>
      </div>
    {:else if items.length === 0 && loading}
      <p class="ur-muted">Chargement des demandes…</p>
    {:else if items.length === 0 && totalPages !== null}
      <p class="ur-muted">{segment.empty}</p>
    {/if}
    {#if items.length > 0}
      <ul>
        {#each items as r (r.id)}
          <li>
            <button
              type="button"
              class="row"
              aria-current={selected?.id === r.id ? "true" : undefined}
              onclick={() => select(r)}
            >
              <Thumb image={r.media?.poster} />
              <span class="text">
                <span class="title">{r.media?.title ?? `Demande n° ${r.id}`}</span>
                <span class="meta ur-muted">{meta(r)}</span>
              </span>
              <span class="ur-pill ur-tone-{statusTone(r.status)}">
                {requestStatusLabel(r.status)}
              </span>
            </button>
          </li>
        {/each}
      </ul>
      {#if more && !error}
        <button
          type="button"
          class="ur-btn more"
          disabled={loading}
          onclick={() => loadPage(page + 1)}
        >
          {loading ? "Chargement…" : "Afficher plus"}
        </button>
      {/if}
    {/if}
  </div>

  {#if selected}
    {@const r = detail ?? selected}
    {@const playable = r.media ? embyItem(r.media) : null}
    <aside class="panel" aria-labelledby="ur-request-title">
      {#if r.media?.backdrop}
        <img
          class="art"
          src={r.media.backdrop.src}
          srcset={r.media.backdrop.srcset}
          sizes="420px"
          alt=""
          decoding="async"
        />
      {:else if r.media?.poster}
        <Thumb image={r.media.poster} width={120} />
      {/if}
      <h2 id="ur-request-title">{r.media?.title ?? `Demande n° ${r.id}`}</h2>
      <p class="ur-muted">
        {typeLabel(r.type)}{#if r.seasons.length > 0}
          · Saison{r.seasons.length > 1 ? "s" : ""} {r.seasons.join(", ")}{/if}
        · {eventDate(r.createdAt)}
      </p>
      <span class="ur-pill ur-tone-{statusTone(r.status)}">
        {requestStatusLabel(r.status)}
      </span>
      {#if r.declineReason}
        <p class="reason">Motif du refus : {r.declineReason}</p>
      {/if}
      {#if r.failureReason}
        <p class="reason">Cause de l'échec : {r.failureReason}</p>
      {/if}

      <div class="actions">
        {#if playable}
          {@const item = playable}
          <button type="button" class="ur-btn primary" onclick={() => onPlay(item)}>
            Lire
          </button>
        {/if}
        {#if r.status === "pending"}
          {#if confirming}
            <span class="confirm" role="group" aria-label="Confirmer l'annulation">
              <span>Annuler cette demande ?</span>
              <button
                type="button"
                class="ur-btn danger"
                disabled={cancelling}
                onclick={() => cancel(r)}
              >
                {cancelling ? "Annulation…" : "Oui, annuler"}
              </button>
              <button
                type="button"
                class="ur-btn"
                disabled={cancelling}
                onclick={() => (confirming = false)}
              >
                Non
              </button>
            </span>
          {:else}
            <button type="button" class="ur-btn" onclick={() => (confirming = true)}>
              Annuler la demande
            </button>
          {/if}
        {/if}
      </div>
      {#if cancelError}<p class="ur-error" role="alert">{cancelError.message}</p>{/if}

      {#if detail}
        <RequestTimeline events={detail.events} />
      {:else if detailError}
        <div class="state" role="alert">
          <p>{detailError.message}</p>
          <button type="button" class="ur-btn" onclick={() => select(r)}>Réessayer</button>
        </div>
      {:else}
        <p class="ur-muted" aria-busy="true">Chargement de l'historique…</p>
      {/if}
    </aside>
  {/if}
</div>

<style>
  p {
    margin: 0;
  }
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(300px, 420px);
    gap: var(--space-6);
    align-items: start;
    margin-top: var(--space-6);
  }
  @media (max-width: 900px) {
    .layout {
      grid-template-columns: 1fr;
    }
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-2);
  }
  .row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px solid transparent;
    border-radius: var(--radius-md);
    background: var(--color-surface);
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .row:hover {
    background: var(--color-elevated);
  }
  .row[aria-current="true"] {
    border-color: var(--color-accent);
    background: var(--color-elevated);
  }
  .text {
    flex: 1;
    min-width: 0;
    display: grid;
    gap: 2px;
  }
  .title {
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta {
    font-size: 13px;
  }
  .more {
    margin-top: var(--space-4);
  }
  .panel {
    position: sticky;
    top: 0;
    display: grid;
    gap: var(--space-3);
    justify-items: start;
    padding: var(--space-4);
    border-radius: var(--radius-lg);
    background: var(--color-surface);
  }
  .art {
    width: 100%;
    aspect-ratio: 16 / 9;
    object-fit: cover;
    border-radius: var(--radius-md);
  }
  h2 {
    margin: 0;
    font-size: 22px;
  }
  .reason {
    color: var(--color-text-secondary);
  }
  .actions,
  .confirm {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .ur-btn.danger {
    background: var(--color-danger);
    color: #000;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
</style>
