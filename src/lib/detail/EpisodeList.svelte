<script lang="ts">
// Season switcher and paged episode list of a series (DESIGN.md, Series).
import { onMount, untrack } from "svelte";
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

interface Props {
  seriesId: string;
  /** Bumped when the connection comes back (ADR 0012): failures are retried. */
  refresh: number;
  onError: (error: EmbyError) => EmbyError;
  /** `resume`: start where the server says the episode was left. */
  onPlay: (episodeId: string, resume: boolean) => void;
  /** Episode being opened by the server, if any. */
  starting: string | null;
}

let { seriesId, refresh, onError, onPlay, starting }: Props = $props();

/** Long anime seasons exist; the rest comes with "Afficher plus d'épisodes". */
const EPISODE_PAGE = 50;

let seasons = $state<Season[] | null>(null);
let seasonsError = $state<EmbyError | null>(null);
let selected = $state<Season | null>(null);
let pager = $state(emptyPager<Episode>());
let loading = $state(false);
let pageError = $state<EmbyError | null>(null);
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

function episodeMeta(e: Episode): string {
  return [formatMinutes(e.runtimeMinutes), formatDate(e.premiereDate)]
    .filter(Boolean)
    .join(" · ");
}

/** New state of a played episode (ADR 0008), patched in place: the list, its
 * scroll position and the focused episode stay as they are. */
function onStopped(state: PlaybackStopped) {
  const episode = pager.items.find((e) => e.id === state.itemId);
  if (!episode) return;
  episode.played = state.played;
  episode.resume = state.resume;
  // Unwatched counts of the seasons changed too.
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

onMount(() => {
  void loadSeasons();
  const unlisten = embyApi.onPlaybackStopped(onStopped);
  return () => void unlisten.then((stop) => stop());
});
</script>

<section class="episodes" aria-labelledby="episodes-title">
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

    <ol class="list" aria-label={selected?.name} aria-busy={loading}>
      {#each pager.items as e (e.id)}
        {@const number = episodeNumber(e)}
        {@const meta = episodeMeta(e)}
        <li class="episode">
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
                sizes="(max-width: 800px) 40vw, 280px"
                alt=""
                loading="lazy"
                decoding="async"
              />
            {/if}
            {#if e.played}
              <span class="badge" title="Déjà vu">
                <svg viewBox="0 0 16 16" aria-hidden="true"
                  ><path d="M3 8.5 6.5 12 13 4.5" /></svg
                >
              </span>
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
          <div class="body">
            <p class="kicker">{[number, meta].filter(Boolean).join(" · ")}</p>
            <h3>{e.name}</h3>
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
    font-size: 20px;
  }
  .note,
  .season-name {
    color: var(--color-text-secondary);
  }
  .seasons {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-bottom: var(--space-4);
  }
  .seasons button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-4);
    border-radius: 999px;
    border: 1px solid var(--color-elevated);
    background: transparent;
    color: var(--color-text-secondary);
    font: inherit;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .seasons button:hover {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .seasons button[aria-pressed="true"] {
    background: var(--color-text);
    border-color: var(--color-text);
    color: var(--color-bg);
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
    gap: var(--space-4);
  }
  .episode {
    display: grid;
    grid-template-columns: minmax(160px, 280px) 1fr;
    gap: var(--space-4);
    align-items: start;
  }
  .thumb {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-sm);
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
  button.thumb[aria-disabled="true"] {
    cursor: progress;
  }
  button.thumb:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
    background: rgb(11 13 17 / 70%);
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
  .badge {
    position: absolute;
    top: var(--space-2);
    right: var(--space-2);
    width: 22px;
    height: 22px;
    border-radius: 11px;
    display: grid;
    place-items: center;
    background: var(--color-success);
    color: #062014;
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
    left: 0;
    right: 0;
    bottom: 0;
    height: 4px;
    background: rgb(11 13 17 / 70%);
  }
  .progress span {
    display: block;
    height: 100%;
    background: var(--color-accent);
  }
  .body {
    min-width: 0;
  }
  .kicker {
    margin: 0;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  h3 {
    margin: 2px 0 var(--space-1);
    font-size: 15px;
  }
  .overview {
    margin: 0;
    font-size: 13px;
    line-height: 1.5;
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
    margin-top: var(--space-4);
    background: var(--color-elevated);
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
  @media (max-width: 800px) {
    .episode {
      grid-template-columns: 40% 1fr;
      gap: var(--space-3);
    }
  }
</style>
