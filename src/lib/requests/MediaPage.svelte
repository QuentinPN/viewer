<script lang="ts">
// Page of an Request title (Fiche A), laid out as the Emby film page:
// backdrop with its scrim, round back button (Échap), title, metadata, genres,
// status of the title and of each season, « Lire » when it is on Emby,
// « Demander » when something is left to request, « Regarder maintenant »
// (its Stremio sources, an episode picked first for a series) when the title
// has an IMDb id and a Stremio account is linked, « Bande-annonce » when the
// title has one (its YouTube page in the browser), synopsis and cast.
import { onMount, tick, untrack } from "svelte";
import type { MediaItem, PlaybackStart } from "../emby/types";
import { stremioState } from "../stremio/api";
import EpisodePicker from "../stremio/EpisodePicker.svelte";
import { externalTarget } from "../stremio/episodes";
import { sourcesReopen } from "../stremio/reopen.svelte";
import SourcesPanel from "../stremio/SourcesPanel.svelte";
import { stremioUsable } from "../stremio/status";
import type { SourcesTarget, StremioState } from "../stremio/types";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import { mediaStatusText, statusTone } from "./labels";
import RequestDialog from "./RequestDialog.svelte";
import { canAsk, embyItem } from "./seasons";
import { rememberStatus } from "./statusOverrides.svelte";
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
  /** « Regarder maintenant »: a Stremio stream is loaded in the player. */
  onStream: (start: PlaybackStart) => void;
}

let { media, viewer, onClose, onPlay, onStream }: Props = $props();

let details = $state<MediaDetails | null>(null);
let error = $state<RequestsError | null>(null);
let asking = $state(false);
let notice = $state("");
let trailerError = $state("");
let backdropFailed = $state(false);
let section = $state<HTMLElement | null>(null);
let heading = $state<HTMLElement | null>(null);
let askButton = $state<HTMLButtonElement | null>(null);
/** Stremio account state, read once without waiting; null: no button. */
let stremio = $state<StremioState | null>(null);
/** « Choisir un épisode » is open. */
let picking = $state(false);
/** Season of the last episode picked: the picker opens on it again. */
let pickedSeason = $state<number | null>(null);
/** « Autres sources » is open for this title, with the message over its list. */
let sources = $state.raw<{
  target: SourcesTarget;
  notice: string | null;
} | null>(null);
/** The title of the stream last played from here, for a reopen on failure. */
let streamed: { key: string; target: SourcesTarget } | null = null;
let watchButton = $state<HTMLButtonElement | null>(null);

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
const imdb = $derived(details?.externalIds.imdb ?? null);
const watchable = $derived(imdb !== null && stremioUsable(stremio));
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
    rememberStatus(d);
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

async function openTrailer(key: string) {
  trailerError = "";
  try {
    await requestsApi.openTrailer(key);
  } catch (e) {
    trailerError = `Impossible d'ouvrir la bande-annonce : ${toRequestsError(e).message}`;
  }
}

async function closeDialog() {
  asking = false;
  await tick();
  askButton?.focus();
}

/** A film lists its sources; a series asks for the episode first. */
function watchNow() {
  if (!details || !imdb) return;
  if (details.type === "movie")
    sources = { target: externalTarget(imdb, details.title), notice: null };
  else picking = true;
}

async function focusWatch() {
  await tick();
  watchButton?.focus({ preventScroll: true });
}

function pickEpisode(pick: { season: number; episode: number }) {
  picking = false;
  if (!details || !imdb) return;
  pickedSeason = pick.season;
  sources = {
    target: externalTarget(imdb, details.title, pick),
    notice: null,
  };
}

async function closePicker() {
  if (!picking) return;
  picking = false;
  await focusWatch();
}

async function closeSources() {
  sources = null;
  await focusWatch();
}

/** The panel closes first: the player gives the focus back to the button. */
async function playSource(target: SourcesTarget, start: PlaybackStart) {
  streamed = { key: start.itemId, target };
  await closeSources();
  onStream(start);
}

// A Stremio stream played from here failed: its list opens again, read anew.
$effect(() => {
  if (!sourcesReopen.request) return;
  untrack(() => {
    const last = streamed;
    if (!last || sources || picking || !section?.checkVisibility()) return;
    const notice = sourcesReopen.take(last.key);
    if (notice !== null) sources = { target: last.target, notice };
  });
});

function onKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || asking || picking || sources) return;
  if (!section?.checkVisibility()) return;
  if (e.key === "Escape" || (e.altKey && e.key === "ArrowLeft")) {
    e.preventDefault();
    onClose();
  }
}

onMount(() => {
  void load();
  void tick().then(() => heading?.focus({ preventScroll: true }));
  void stremioState().then((s) => (stremio = s));
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
        class="hero-backdrop"
        src={backdrop.src}
        srcset={backdrop.srcset}
        sizes="100vw"
        alt=""
        decoding="async"
        onerror={() => (backdropFailed = true)}
      />
    {/if}
    <div class="hero-scrim"></div>

    <div class="hero-topbar">
      <button
        type="button"
        class="round-back"
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
          {#if watchable}
            <button
              type="button"
              class="ur-btn large"
              class:primary={!playable && !askable}
              aria-haspopup="dialog"
              bind:this={watchButton}
              onclick={watchNow}
            >
              Regarder maintenant
            </button>
          {/if}
          {#if details.trailerKey}
            {@const key = details.trailerKey}
            <button type="button" class="ur-btn large" onclick={() => openTrailer(key)}>
              Bande-annonce
            </button>
          {/if}
        {/if}
      </div>
      {#if notice}<p class="notice ur-tone-success" role="status">{notice}</p>{/if}
      {#if trailerError}<p class="notice ur-tone-danger" role="alert">{trailerError}</p>{/if}
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

{#if picking && details}
  <EpisodePicker
    seriesName={details.title}
    seasons={details.seasons}
    initialSeason={pickedSeason}
    onClose={() => void closePicker()}
    onPick={pickEpisode}
  />
{/if}

{#if sources}
  {@const target = sources.target}
  <SourcesPanel
    {target}
    notice={sources.notice}
    onClose={() => void closeSources()}
    onPlay={(start) => void playSource(target, start)}
  />
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
