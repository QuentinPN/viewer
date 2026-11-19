<script lang="ts">
// Page of an Request title (Fiche A), laid out as the Emby film page:
// backdrop with its scrim, round back button (Échap), title, metadata, genres,
// status of the title and of each season, « Lire » when it is on Emby,
// « Demander » when something is left to request, synopsis and cast.
import { onMount, tick } from "svelte";
import type { MediaItem } from "../emby/types";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import { mediaStatusText, statusTone } from "./labels";
import RequestDialog from "./RequestDialog.svelte";
import { canAsk, embyItem } from "./seasons";
import type {
  MediaDetails,
  MediaSummary,
  RequestsError,
  Viewer,
} from "./types";

interface Props {
  media: MediaSummary;
  viewer: Viewer | null;
  onClose: () => void;
  /** « Lire »: the Emby title opens in the existing detail page. */
  onPlay: (item: MediaItem) => void;
}

let { media, viewer, onClose, onPlay }: Props = $props();

let details = $state<MediaDetails | null>(null);
let error = $state<RequestsError | null>(null);
let asking = $state(false);
let notice = $state("");
let backdropFailed = $state(false);
let section = $state<HTMLElement | null>(null);
let heading = $state<HTMLElement | null>(null);
let askButton = $state<HTMLButtonElement | null>(null);

const shown = $derived(details ?? media);
const backdrop = $derived(
  shown.backdrop && !backdropFailed ? shown.backdrop : null,
);
const status = $derived(shown.status ?? null);
const playable = $derived(details ? embyItem(details) : null);
const askable = $derived(
  details ? canAsk(details, viewer?.capabilities ?? null) : false,
);
const seasons = $derived(
  details?.seasons.filter((s) => s.seasonNumber > 0) ?? [],
);
const meta = $derived(
  [
    shown.year ? String(shown.year) : null,
    details?.runtime ? `${details.runtime} min` : null,
    shown.type === "movie" ? "Film" : "Série",
    details && shown.type === "tv" && seasons.length > 0
      ? `${seasons.length} saison${seasons.length > 1 ? "s" : ""}`
      : null,
  ].filter((p): p is string => p !== null),
);

async function load() {
  error = null;
  try {
    const d = await requestsApi.media(media.type, media.tmdbId);
    details = d;
  } catch (e) {
    error = toRequestsError(e);
  }
}

async function requested() {
  asking = false;
  notice = "Demande envoyée";
  await load();
  await tick();
  heading?.focus({ preventScroll: true });
}

async function closeDialog() {
  asking = false;
  await tick();
  askButton?.focus();
}

function onKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || asking || !section?.checkVisibility()) return;
  if (e.key === "Escape" || (e.altKey && e.key === "ArrowLeft")) {
    e.preventDefault();
    onClose();
  }
}

onMount(() => {
  void load();
  void tick().then(() => heading?.focus({ preventScroll: true }));
});
</script>

<svelte:window onkeydown={onKeydown} />

<section
  class="page"
  aria-labelledby="ur-media-title"
  aria-busy={!details && !error}
  bind:this={section}
>
  <div class="hero">
    {#if backdrop}
      <img
        class="backdrop"
        src={backdrop.src}
        srcset={backdrop.srcset}
        sizes="100vw"
        alt=""
        decoding="async"
        onerror={() => (backdropFailed = true)}
      />
    {/if}
    <div class="scrim"></div>

    <div class="topbar">
      <button
        type="button"
        class="back"
        aria-label="Retour"
        title="Retour (Échap)"
        onclick={onClose}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
      </button>
    </div>

    <div class="headline">
      <h1 id="ur-media-title" tabindex="-1" bind:this={heading}>{shown.title}</h1>
      {#if meta.length > 0}<p class="meta">{meta.join(" · ")}</p>{/if}
      {#if details && details.genres.length > 0}
        <ul class="genres" aria-label="Genres">
          {#each details.genres as g (g.id)}<li>{g.name}</li>{/each}
        </ul>
      {/if}
      {#if status}
        <p class="status ur-tone-{statusTone(status)}">
          {mediaStatusText(status, shown.progress)}
        </p>
      {/if}
      {#if seasons.some((s) => s.status)}
        <ul class="seasons" aria-label="Saisons">
          {#each seasons as s (s.seasonNumber)}
            {#if s.status}
              <li>
                Saison {s.seasonNumber} ·
                <span class="ur-tone-{statusTone(s.status)}"
                  >{mediaStatusText(s.status, s.progress)}</span
                >
              </li>
            {/if}
          {/each}
        </ul>
      {/if}

      <div class="actions">
        {#if playable}
          {@const item = playable}
          <button type="button" class="ur-btn primary large" onclick={() => onPlay(item)}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11l9-5.5z" /></svg>
            Lire
          </button>
        {/if}
        {#if details}
          {#if askable}
            <button
              type="button"
              class="ur-btn large"
              class:primary={!playable}
              bind:this={askButton}
              onclick={() => {
                notice = "";
                asking = true;
              }}
            >
              Demander
            </button>
          {:else if !playable}
            <button type="button" class="ur-btn large" disabled>Rien à demander</button>
          {/if}
        {/if}
      </div>
      {#if notice}<p class="notice ur-tone-success" role="status">{notice}</p>{/if}
    </div>
  </div>

  <div class="body">
    {#if error}
      <div class="state" role="alert">
        <p>Impossible de charger ce titre : {error.message}</p>
        <button type="button" class="ur-btn" onclick={load}>Réessayer</button>
      </div>
    {/if}
    {#if shown.overview}
      <p class="overview">{shown.overview}</p>
    {/if}
    {#if details && details.cast.length > 0}
      <section aria-labelledby="ur-media-cast">
        <h2 id="ur-media-cast">Distribution</h2>
        <ul class="cast">
          {#each details.cast.slice(0, 16) as c (c.id)}
            <li>
              <span class="photo">
                {#if c.profile}
                  <img
                    src={c.profile.src}
                    srcset={c.profile.srcset}
                    sizes="88px"
                    alt=""
                    loading="lazy"
                    decoding="async"
                  />
                {/if}
              </span>
              <span class="name">{c.name}</span>
              {#if c.character}<span class="role ur-muted">{c.character}</span>{/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}
  </div>
</section>

{#if asking && details}
  <RequestDialog {details} onClose={closeDialog} onDone={requested} />
{/if}

<style>
  .page {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    --gutter: clamp(var(--space-4), 5vw, 80px);
    background: var(--color-bg);
  }
  .hero {
    position: relative;
    min-height: clamp(420px, 68vh, 760px);
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    overflow: hidden;
  }
  .backdrop {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 25%;
  }
  .scrim {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(90deg, rgb(0 0 0 / 90%) 0%, rgb(0 0 0 / 40%) 50%, rgb(0 0 0 / 0%) 75%),
      linear-gradient(0deg, var(--color-bg) 0%, rgb(0 0 0 / 0%) 45%);
  }
  .topbar {
    position: relative;
    padding: var(--space-4) clamp(var(--space-4), 3vw, 32px);
  }
  .back {
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 1px solid var(--separator);
    border-radius: 50%;
    background: var(--material);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    cursor: pointer;
  }
  .back:hover {
    background: var(--material-thick);
  }
  .back:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .back svg,
  .actions svg {
    width: 18px;
    height: 18px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .actions svg {
    fill: currentColor;
    stroke: none;
  }
  .headline {
    position: relative;
    display: grid;
    gap: var(--space-3);
    justify-items: start;
    max-width: min(720px, 100%);
    padding: var(--space-6) var(--gutter) 40px;
  }
  h1 {
    margin: 0;
    font-size: clamp(36px, 5vw, 64px);
    line-height: 1.05;
    outline: none;
  }
  p {
    margin: 0;
  }
  .meta {
    color: var(--color-text-secondary);
  }
  .genres,
  .seasons {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .genres li {
    padding: 4px 12px;
    border-radius: var(--radius-pill);
    background: var(--fill-glass);
    font-size: 13px;
  }
  .seasons {
    column-gap: var(--space-4);
    font-size: 14px;
    color: var(--color-text-secondary);
  }
  .status {
    font-weight: 600;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-3);
    margin-top: var(--space-2);
  }
  .notice {
    font-size: 14px;
  }
  .body {
    display: grid;
    gap: var(--space-6);
    padding: 0 var(--gutter) 48px;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  .overview {
    max-width: 70ch;
    font-size: 17px;
    line-height: 1.5;
    color: var(--color-text-secondary);
  }
  h2 {
    margin: 0 0 var(--space-3);
    font-size: 22px;
  }
  .cast {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
    gap: var(--space-4);
  }
  .cast li {
    display: grid;
    justify-items: center;
    gap: 2px;
    text-align: center;
    font-size: 13px;
  }
  .photo {
    width: 88px;
    height: 88px;
    border-radius: 50%;
    overflow: hidden;
    background: var(--color-surface);
    margin-bottom: var(--space-1);
  }
  .photo img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .name {
    font-weight: 600;
  }
</style>
