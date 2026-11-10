<script lang="ts">
// Season switcher and paged episode list of a series (DESIGN.md, Series).
import { onMount, tick, untrack } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  Episode,
  PlaybackStopped,
  Season,
} from "../emby/types";
import { formatMinutes } from "../format/duration";
import { appendPage, emptyPager } from "../library/pager";
import { defaultSeason, episodeNumber, formatDate } from "./format";
import { changePlayed, playedAction } from "./watched";

interface Props {
  seriesId: string;
  /** Bumped when the connection comes back (ADR 0012): failures are retried. */
  refresh: number;
  onError: (error: EmbyError) => EmbyError;
  /** `resume`: start where the server says the episode was left. */
  onPlay: (episodeId: string, resume: boolean) => void;
  /** The title opens the episode page (ADR 0016); the thumbnail plays. */
  onOpen: (episode: Episode) => void;
  /** Episode being opened by the server, if any. */
  starting: string | null;
}

let { seriesId, refresh, onError, onPlay, onOpen, starting }: Props = $props();

/** Long anime seasons exist; the rest comes with "Afficher plus d'épisodes". */
const EPISODE_PAGE = 50;

let seasons = $state<Season[] | null>(null);
let seasonsError = $state<EmbyError | null>(null);
let selected = $state<Season | null>(null);
let pager = $state(emptyPager<Episode>());
let loading = $state(false);
let pageError = $state<EmbyError | null>(null);
let root = $state<HTMLElement | null>(null);
/** Episodes and seasons whose watched change waits for the server (ADR 0096). */
let saving = $state<Record<string, true>>({});
let playedError = $state<{ name: string; error: EmbyError } | null>(null);
// Bumped on every season switch: answers for a previous season are dropped.
let generation = 0;

let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(() => {
    if (seasonsError) void loadSeasons();
    else if (pageError) void loadMore();
  });
});

async function loadSeasons() {
  seasons = null;
  seasonsError = null;
  try {
    seasons = await embyApi.seasons(seriesId);
    const first = defaultSeason(seasons);
    if (first) select(first);
  } catch (e) {
    seasonsError = onError(toEmbyError(e));
  }
}

function select(season: Season) {
  if (selected?.id === season.id && pager.total !== null) return;
  generation += 1;
  selected = season;
  pager = emptyPager<Episode>();
  pageError = null;
  loading = false;
  void loadMore();
}

async function loadMore() {
  const season = selected;
  if (!season || loading || pager.done) return;
  const gen = generation;
  loading = true;
  pageError = null;
  try {
    const page = await embyApi.episodes(
      seriesId,
      season.id,
      pager.next,
      EPISODE_PAGE,
    );
    if (gen === generation) pager = appendPage(pager, page);
  } catch (e) {
    if (gen === generation) pageError = onError(toEmbyError(e));
  } finally {
    if (gen === generation) loading = false;
  }
}

/** Lets a season page arrive before giving up, without blocking forever. */
async function settled() {
  for (let waited = 0; (loading || seasons === null) && waited < 15000; ) {
    await new Promise((r) => setTimeout(r, 50));
    waited += 50;
  }
  await tick();
}

/**
 * Back from an episode page (ADR 0016): shows the episode's season and focuses
 * its title. Not in the loaded pages: the focus goes to its season button.
 */
export async function reveal(episodeId: string, seasonId: string | null) {
  await settled();
  const season = seasons?.find((s) => s.id === seasonId);
  if (season && season.id !== selected?.id) {
    select(season);
    await settled();
  }
  const title = root?.querySelector<HTMLElement>(
    `[data-episode-id="${CSS.escape(episodeId)}"] .open`,
  );
  const fallback = root?.querySelector<HTMLElement>(
    '.seasons button[aria-pressed="true"]',
  );
  (title ?? fallback)?.focus();
}

function episodeMeta(e: Episode): string {
  return [formatMinutes(e.runtimeMinutes), formatDate(e.premiereDate)]
    .filter(Boolean)
    .join(" · ");
}

/** Unwatched counts of the seasons, read again in place. */
function refreshSeasons() {
  embyApi
    .seasons(seriesId)
    .then((fresh) => {
      for (const s of seasons ?? []) {
        const f = fresh.find((x) => x.id === s.id);
        if (f) {
          s.played = f.played;
          s.unplayedCount = f.unplayedCount;
        }
      }
    })
    .catch(() => {});
}

/** Watched state of the episodes shown, read again in place. */
async function refreshEpisodes() {
  const season = selected;
  if (!season || pager.items.length === 0) return;
  const gen = generation;
  try {
    const page = await embyApi.episodes(
      seriesId,
      season.id,
      0,
      Math.min(pager.items.length, 200),
    );
    if (gen !== generation) return;
    for (const fresh of page.items) {
      const e = pager.items.find((x) => x.id === fresh.id);
      if (e) {
        e.played = fresh.played;
        e.resume = fresh.resume;
      }
    }
  } catch {
    // The list keeps what it showed; the next change or reload corrects it.
  }
}

/** New state of a played episode (ADR 0008), patched in place: the list, its
 * scroll position and the focused episode stay as they are. A whole season or
 * the series marked (ADR 0096): its episodes are read again. */
function onStopped(state: PlaybackStopped) {
  const episode = pager.items.find((e) => e.id === state.itemId);
  if (episode) {
    episode.played = state.played;
    episode.resume = state.resume;
    // Unwatched counts of the seasons changed too.
    refreshSeasons();
  } else if (
    state.itemId === seriesId ||
    seasons?.some((s) => s.id === state.itemId)
  ) {
    refreshSeasons();
    void refreshEpisodes();
  }
}

/** Marks an episode played or unplayed (ADR 0096); its thumbnail does not play. */
async function toggleEpisode(e: Episode) {
  if (saving[e.id]) return;
  saving[e.id] = true;
  playedError = null;
  const before = e.resume;
  try {
    const state = await changePlayed(
      !e.played,
      (p) => {
        e.played = p;
        e.resume = p ? null : before;
      },
      (p) => embyApi.setPlayed(e.id, p),
    );
    e.resume = state.resume;
  } catch (err) {
    playedError = { name: e.name, error: onError(toEmbyError(err)) };
  } finally {
    delete saving[e.id];
  }
}

/** Marks the season shown played or unplayed; its episodes follow on the event. */
async function toggleSeason(season: Season) {
  if (saving[season.id]) return;
  saving[season.id] = true;
  playedError = null;
  try {
    await changePlayed(
      !season.played,
      (p) => {
        season.played = p;
      },
      (p) => embyApi.setPlayed(season.id, p),
    );
  } catch (err) {
    playedError = { name: season.name, error: onError(toEmbyError(err)) };
  } finally {
    delete saving[season.id];
  }
}

onMount(() => {
  void loadSeasons();
  const unlisten = embyApi.onPlaybackStopped(onStopped);
  return () => void unlisten.then((stop) => stop());
});
</script>

<section class="episodes" aria-labelledby="episodes-title" bind:this={root}>
  <h2 id="episodes-title">Épisodes</h2>

  {#if seasonsError}
    <div class="state" role="alert">
      <p>{seasonsError.message}</p>
      {#if errorHint(seasonsError)}<p class="hint">{errorHint(seasonsError)}</p>{/if}
      <button type="button" class="primary" onclick={loadSeasons}>Réessayer</button>
    </div>
  {:else if seasons === null}
    <p class="note" aria-busy="true">Chargement des saisons…</p>
  {:else if seasons.length === 0}
    <p class="note">Aucune saison disponible pour cette série.</p>
  {:else}
    {#if seasons.length > 1}
      <div class="seasons" role="group" aria-label="Saisons">
        {#each seasons as season (season.id)}
          <button
            type="button"
            aria-pressed={selected?.id === season.id}
            onclick={() => select(season)}
          >
            {season.name}
            {#if season.played}
              <span class="sr-only">, vue</span>
            {:else if season.unplayedCount}
              <span class="unplayed" title="Épisodes non vus">{season.unplayedCount}</span
              ><span class="sr-only"> épisodes non vus</span>
            {/if}
          </button>
        {/each}
      </div>
    {:else if selected}
      <p class="season-name">{selected.name}</p>
    {/if}
    {#if selected}
      {@const season = selected}
      <button
        type="button"
        class="season-played"
        aria-pressed={season.played}
        aria-disabled={saving[season.id] === true}
        aria-label={`Saison « ${season.name} » vue`}
        title={playedAction(season.played, season.name)}
        onclick={() => toggleSeason(season)}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5 6.5 12 13 4.5" /></svg>
        Saison vue
      </button>
    {/if}

    <ol class="list" aria-label={selected?.name} aria-busy={loading}>
      {#each pager.items as e (e.id)}
        {@const number = episodeNumber(e)}
        {@const meta = episodeMeta(e)}
        <li class="episode" data-episode-id={e.id}>
          <button
            type="button"
            class="thumb"
            onclick={() => onPlay(e.id, e.resume !== null)}
            aria-disabled={starting !== null}
            aria-busy={starting === e.id}
            aria-label={`${e.resume ? "Reprendre" : "Lire"} ${number ? `${number} : ` : ""}${e.name}`}
          >
            {#if e.thumb}
              <img
                src={e.thumb.src}
                srcset={e.thumb.srcset}
                sizes="(max-width: 640px) 90vw, 340px"
                alt=""
                loading="lazy"
                decoding="async"
              />
            {/if}
            {#if e.resume?.fraction}
              <span class="progress" aria-hidden="true"
                ><span style:width="{Math.round(e.resume.fraction * 100)}%"></span></span
              >
            {/if}
            <span class="play" class:visible={starting === e.id} aria-hidden="true">
              {#if starting === e.id}
                <span class="spinner"></span>
              {:else}
                <svg viewBox="0 0 16 16"><path d="M4.5 2.8v10.4L13 8z" /></svg>
              {/if}
            </span>
          </button>
          <!-- Beside the thumbnail, not in it: a button in a button is not valid. -->
          <button
            type="button"
            class="badge"
            class:played={e.played}
            aria-pressed={e.played}
            aria-disabled={saving[e.id] === true}
            aria-label={`« ${e.name} » vu`}
            title={playedAction(e.played)}
            onclick={() => toggleEpisode(e)}
          >
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5 6.5 12 13 4.5" /></svg>
          </button>
          <div class="body">
            <p class="kicker">{[number, meta].filter(Boolean).join(" · ")}</p>
            <h3>
              <button type="button" class="open" onclick={() => onOpen(e)}>{e.name}</button>
            </h3>
            {#if e.played}
              <p class="sr-only">Déjà vu</p>
            {:else if e.resume}
              <p class="sr-only">
                Commencé, {Math.round((e.resume.fraction ?? 0) * 100)} % vus
              </p>
            {/if}
            {#if e.overview}<p class="overview">{e.overview}</p>{/if}
          </div>
        </li>
      {/each}
      {#if loading}
        {#each { length: pager.items.length === 0 ? 4 : 2 } as _, i (i)}
          <li class="episode skeleton" aria-hidden="true">
            <div class="thumb"></div>
            <div class="body">
              <div class="sk-line short"></div>
              <div class="sk-line"></div>
            </div>
          </li>
        {/each}
      {/if}
    </ol>

    {#if playedError}
      <div class="state played-error" role="alert">
        <p>« {playedError.name} » : statut « vu » non modifié. {playedError.error.message}</p>
        {#if errorHint(playedError.error)}<p class="hint">{errorHint(playedError.error)}</p>{/if}
        <button type="button" class="more" onclick={() => (playedError = null)}>Fermer</button>
      </div>
    {/if}

    {#if pageError}
      <div class="state" role="alert">
        <p>{pageError.message}</p>
        {#if errorHint(pageError)}<p class="hint">{errorHint(pageError)}</p>{/if}
        <button type="button" class="primary" onclick={loadMore}>Réessayer</button>
      </div>
    {:else if pager.total === 0}
      <p class="note">Aucun épisode dans cette saison.</p>
    {:else if !pager.done && !loading && pager.total !== null}
      <button type="button" class="more" onclick={loadMore}>
        Afficher plus d'épisodes ({pager.items.length} sur {pager.total})
      </button>
    {/if}
  {/if}
</section>

<style>
  .episodes {
    margin-top: var(--space-6);
  }
  h2 {
    margin: 0 0 var(--space-4);
    font-size: 22px;
    font-weight: 600;
  }
  .note,
  .season-name {
    color: var(--color-text-secondary);
  }
  .seasons {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: var(--space-6);
  }
  .seasons button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    height: 36px;
    padding: 0 18px;
    border: 0;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 15px;
    font-weight: 500;
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  .seasons button:hover {
    background: var(--fill-glass);
    color: var(--color-text);
  }
  .seasons button[aria-pressed="true"] {
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-weight: 600;
  }
  .unplayed {
    min-width: 20px;
    padding: 0 6px;
    border-radius: 10px;
    background: var(--color-accent);
    color: #04121b;
    font-size: 12px;
    font-weight: 700;
    text-align: center;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 32px 28px;
  }
  .episode {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-width: 0;
  }
  .thumb {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
  }
  button.thumb {
    width: 100%;
    padding: 0;
    border: 0;
    display: block;
    color: inherit;
    font: inherit;
    cursor: pointer;
  }
  button.thumb {
    transition:
      transform var(--motion-lift) var(--ease-spring),
      box-shadow var(--motion-lift) var(--ease-spring);
  }
  button.thumb[aria-disabled="true"] {
    cursor: progress;
  }
  button.thumb:hover,
  button.thumb:focus-visible {
    transform: scale(1.04);
    box-shadow:
      0 20px 40px rgb(0 0 0 / 65%),
      0 0 0 1px rgb(255 255 255 / 28%);
  }
  button.thumb:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 3px;
  }
  @media (prefers-reduced-motion: reduce) {
    button.thumb {
      transition: none;
    }
    button.thumb:hover,
    button.thumb:focus-visible {
      transform: none;
    }
  }
  .play {
    position: absolute;
    inset: 0;
    margin: auto;
    width: 44px;
    height: 44px;
    border-radius: 22px;
    display: grid;
    place-items: center;
    background: var(--material);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    opacity: 0;
    transition: opacity var(--motion-fast);
  }
  button.thumb:hover .play,
  button.thumb:focus-visible .play,
  .play.visible {
    opacity: 1;
  }
  .play svg {
    width: 18px;
    height: 18px;
    margin-left: 2px;
    fill: currentColor;
  }
  .spinner {
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: 2px solid rgb(255 255 255 / 30%);
    border-top-color: var(--color-text);
    animation: spin 0.8s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
    }
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  /* Over the thumbnail's top right corner; a watched episode always shows it,
     the others on hover or keyboard focus (Tab reaches it after the thumbnail). */
  .badge {
    position: absolute;
    z-index: 1;
    top: var(--space-2);
    right: var(--space-2);
    width: 28px;
    height: 28px;
    padding: 0;
    border: 0;
    border-radius: 14px;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 55%);
    backdrop-filter: blur(12px);
    color: #fff;
    font: inherit;
    cursor: pointer;
    opacity: 0;
    transition:
      opacity var(--motion-fast),
      background var(--motion-fast);
  }
  .badge.played,
  .episode:hover .badge,
  .badge:focus-visible {
    opacity: 1;
  }
  .badge:hover {
    background: rgb(0 0 0 / 75%);
  }
  .badge:not(.played) svg {
    opacity: 0.7;
  }
  .badge[aria-disabled="true"] {
    cursor: progress;
  }
  .season-played {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    margin: calc(-1 * var(--space-3)) 0 var(--space-6);
    height: 32px;
    padding: 0 14px;
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--fill-glass);
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .season-played:hover {
    background: var(--fill-glass-hover);
    color: var(--color-text);
  }
  .season-played svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
    opacity: 0.55;
  }
  .season-played[aria-pressed="true"] {
    color: var(--color-text);
  }
  .season-played[aria-pressed="true"] svg {
    stroke: var(--color-accent);
    opacity: 1;
  }
  .season-played[aria-disabled="true"] {
    cursor: progress;
  }
  .played-error {
    margin-bottom: var(--space-4);
  }
  .badge svg {
    width: 13px;
    height: 13px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .progress {
    position: absolute;
    left: 12px;
    right: 12px;
    bottom: 10px;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 30%);
    overflow: hidden;
  }
  .progress span {
    display: block;
    height: 100%;
    background: #fff;
  }
  .body {
    position: relative;
    min-width: 0;
    border-radius: var(--radius-sm);
  }
  .body:has(.open:focus-visible) {
    outline: 2px solid var(--color-accent);
    outline-offset: 4px;
  }
  .kicker {
    margin: 0;
    font-size: 13px;
    color: var(--color-text-muted);
  }
  h3 {
    margin: 2px 0 var(--space-1);
    font-size: 16px;
    font-weight: 600;
  }
  /* The whole text opens the page; the thumbnail keeps playing. */
  .open {
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    font-weight: inherit;
    text-align: start;
  }
  .open::after {
    content: "";
    position: absolute;
    inset: 0;
  }
  .body:hover .open {
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .open:focus-visible {
    outline: none;
  }
  .overview {
    margin: 0;
    font-size: 14px;
    line-height: 1.45;
    color: var(--color-text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .skeleton .thumb,
  .sk-line {
    animation: pulse 1.4s ease-in-out infinite;
  }
  .sk-line {
    height: 12px;
    margin-top: var(--space-2);
    border-radius: 6px;
    background: var(--color-surface);
  }
  .sk-line.short {
    width: 30%;
  }
  @keyframes pulse {
    50% {
      opacity: 0.55;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .skeleton .thumb,
    .sk-line {
      animation: none;
    }
  }
  .state {
    color: var(--color-text-secondary);
  }
  .state p {
    margin: var(--space-1) 0;
  }
  .state p:first-letter {
    text-transform: uppercase;
  }
  .hint {
    font-size: 13px;
    color: var(--color-text-muted);
  }
  button.primary {
    margin-top: var(--space-2);
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
    margin-top: var(--space-6);
    background: var(--fill-glass);
    color: var(--color-text);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    cursor: pointer;
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
