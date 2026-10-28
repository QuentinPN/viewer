<script lang="ts">
// Detail page of a film or series (DESIGN.md, Film detail / Series). Shown over the
// library grid, which stays mounted so its scroll position survives the return.
import { onMount, tick } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  ItemDetail,
  MediaItem,
  PlaybackStart,
  PlaybackStopped,
} from "../emby/types";
import EpisodeList from "./EpisodeList.svelte";
import {
  audioLabel,
  formatBitrate,
  formatDate,
  formatSize,
  metaParts,
  ratingLabel,
  resumeLabel,
  subtitleLabel,
  videoLabel,
} from "./format";

interface Props {
  /** Grid card: title and poster are shown at once while the detail loads. */
  item: MediaItem;
  onClose: () => void;
  /** Returns the error; a rejected token also ends the session. */
  onError: (error: EmbyError) => EmbyError;
  /** Playback started by the server negotiation: show the player. */
  onPlay: (start: PlaybackStart) => void;
}

let { item, onClose, onError, onPlay }: Props = $props();

let detail = $state<ItemDetail | null>(null);
let error = $state<EmbyError | null>(null);
let backdropFailed = $state(false);
let logoFailed = $state(false);
let heading = $state<HTMLElement | null>(null);
let section = $state<HTMLElement | null>(null);
/** Id of the film or episode being opened by the server. */
let starting = $state<string | null>(null);
/** The film is opened from the start rather than resumed. */
let fromStart = $state(false);
let playError = $state<EmbyError | null>(null);
let playButton = $state<HTMLButtonElement | null>(null);
let fromStartButton = $state<HTMLButtonElement | null>(null);

const poster = $derived(detail?.poster ?? item.poster);
const meta = $derived(detail ? metaParts(detail) : []);
const rating = $derived(ratingLabel(detail?.communityRating ?? null));
const premiere = $derived(formatDate(detail?.premiereDate ?? null));

async function load() {
  error = null;
  try {
    const d = await embyApi.itemDetail(item.id);
    if (d.id === item.id) detail = d;
  } catch (e) {
    error = onError(toEmbyError(e));
  }
}

async function play(id: string, resume: boolean) {
  if (starting) return;
  starting = id;
  fromStart = !resume;
  playError = null;
  try {
    onPlay(await embyApi.play(id, resume));
  } catch (e) {
    playError = onError(toEmbyError(e));
  } finally {
    starting = null;
  }
}

/** New watched status and resume point after a playback (ADR 0008). */
async function onStopped(state: PlaybackStopped) {
  const d = detail;
  if (!d) return;
  if (d.kind === "series") {
    // Episode counts changed: refresh quietly, the page stays as it is.
    embyApi
      .itemDetail(d.id)
      .then((fresh) => {
        if (fresh.id === detail?.id) detail = fresh;
      })
      .catch(() => {});
    return;
  }
  if (state.itemId !== d.id) return;
  const hadFocus = document.activeElement === fromStartButton;
  d.played = state.played;
  d.resume = state.resume;
  await tick();
  // "Lire depuis le début" disappears once the film is finished.
  if (hadFocus && !fromStartButton?.isConnected) {
    playButton?.focus({ preventScroll: true });
  }
}

/** Hidden under the player: keys belong to the player then. */
function active() {
  return section?.checkVisibility() ?? false;
}

function onKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || !active()) return;
  if (e.key === "Escape" || (e.altKey && e.key === "ArrowLeft")) {
    e.preventDefault();
    onClose();
  }
}

/** Mouse "back" button. */
function onMouseup(e: MouseEvent) {
  if (e.button === 3 && active()) {
    e.preventDefault();
    onClose();
  }
}

onMount(() => {
  void load();
  void tick().then(() => heading?.focus({ preventScroll: true }));
  const unlisten = embyApi.onPlaybackStopped(onStopped);
  return () => void unlisten.then((stop) => stop());
});
</script>

<svelte:window onkeydown={onKeydown} onmouseup={onMouseup} />

<section
  class="detail"
  aria-labelledby="detail-title"
  aria-busy={!detail && !error}
  bind:this={section}
>
  <div class="hero">
    {#if detail?.backdrop && !backdropFailed}
      <img
        class="backdrop"
        src={detail.backdrop.src}
        srcset={detail.backdrop.srcset}
        sizes="100vw"
        alt=""
        decoding="async"
        onerror={() => (backdropFailed = true)}
      />
    {/if}
    <div class="scrim"></div>

    <div class="topbar">
      <button type="button" class="back" onclick={onClose}>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
        Retour
      </button>
    </div>

    <div class="hero-content">
      <div class="poster">
        {#if poster}
          <img src={poster.src} srcset={poster.srcset} sizes="240px" alt="" decoding="async" />
        {/if}
      </div>

      <div class="headline">
        <h1 id="detail-title" tabindex="-1" bind:this={heading}>
          {#if detail?.logo && !logoFailed}
            <img
              class="logo"
              src={detail.logo.src}
              srcset={detail.logo.srcset}
              sizes="(max-width: 800px) 60vw, 420px"
              alt={detail.name}
              onerror={() => (logoFailed = true)}
            />
          {:else}
            {detail?.name ?? item.name}
          {/if}
        </h1>
        {#if detail?.originalTitle}
          <p class="original" lang="">{detail.originalTitle}</p>
        {/if}

        {#if detail}
          <p class="meta">
            <!-- Svelte trims spaces at the edges of a tag: separators are expressions. -->
            {meta.join(" · ")}
            {#if rating}
              {#if meta.length > 0}{" · "}{/if}
              <span class="rating" title="Note des spectateurs">
                <svg viewBox="0 0 16 16" aria-hidden="true"
                  ><path
                    d="m8 1.8 1.9 3.9 4.3.6-3.1 3 .7 4.3L8 11.6l-3.8 2 .7-4.3-3.1-3 4.3-.6z"
                  /></svg
                ><span class="sr-only">Note des spectateurs : </span>{rating}<span
                  class="sr-only"> sur 10</span
                >
              </span>
            {/if}
          </p>

          {#if detail.genres.length > 0}
            <ul class="genres" aria-label="Genres">
              {#each detail.genres as genre (genre)}<li>{genre}</li>{/each}
            </ul>
          {/if}

          <div class="status">
            {#if detail.played}
              <span class="pill done">Déjà vu</span>
            {:else if detail.unplayedCount}
              <span class="pill"
                >{detail.unplayedCount} épisode{detail.unplayedCount > 1 ? "s" : ""} non vu{detail.unplayedCount >
                1
                  ? "s"
                  : ""}</span
              >
            {/if}
            {#if detail.resume}
              <span class="resume">
                {resumeLabel(detail.resume)}
                {#if detail.resume.fraction != null}
                  <span class="bar" aria-hidden="true"
                    ><span style:width="{Math.round(detail.resume.fraction * 100)}%"></span></span
                  >
                {/if}
              </span>
            {/if}
          </div>

          {#if detail.kind === "movie"}
            <div class="play-actions">
              <button
                type="button"
                class="primary play"
                bind:this={playButton}
                onclick={() => play(detail?.id ?? item.id, detail?.resume != null)}
                aria-disabled={starting !== null}
                aria-busy={starting !== null && !fromStart}
              >
                <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 2.8v10.4L13 8z" /></svg>
                {#if starting && !fromStart}Ouverture…{:else if detail.resume}Reprendre{:else}Lecture{/if}
              </button>
              {#if detail.resume}
                <button
                  type="button"
                  class="secondary from-start"
                  bind:this={fromStartButton}
                  onclick={() => play(detail?.id ?? item.id, false)}
                  aria-disabled={starting !== null}
                  aria-busy={starting !== null && fromStart}
                >
                  {#if starting && fromStart}Ouverture…{:else}Lire depuis le début{/if}
                </button>
              {/if}
            </div>
          {/if}
        {:else if !error}
          <div class="sk-meta" aria-hidden="true"></div>
          <p class="sr-only">Chargement de la fiche…</p>
        {/if}
      </div>
    </div>
  </div>

  <div class="body">
    {#if error}
      <div class="state" role="alert">
        <p class="state-title">Impossible d'afficher cette fiche</p>
        <p>{error.message}</p>
        {#if errorHint(error)}<p class="hint">{errorHint(error)}</p>{/if}
        <div class="actions">
          <button type="button" class="primary" onclick={load}>Réessayer</button>
          <button type="button" class="secondary" onclick={onClose}>Retour à la bibliothèque</button>
        </div>
      </div>
    {:else if detail}
      <div class="columns">
        <div class="story">
          {#if detail.tagline}<p class="tagline">{detail.tagline}</p>{/if}
          {#if detail.overview}
            <p class="overview">{detail.overview}</p>
          {:else}
            <p class="overview muted">Aucun résumé disponible.</p>
          {/if}
        </div>

        <dl class="facts">
          {#if detail.directors.length > 0}
            <div>
              <dt>Réalisation</dt>
              <dd>{detail.directors.join(", ")}</dd>
            </div>
          {/if}
          {#if detail.cast.length > 0}
            <div>
              <dt>Avec</dt>
              <dd>{detail.cast.map((c) => c.name).join(", ")}</dd>
            </div>
          {/if}
          {#if detail.studios.length > 0}
            <div>
              <dt>{detail.kind === "series" ? "Diffusion" : "Studio"}</dt>
              <dd>{detail.studios.join(", ")}</dd>
            </div>
          {/if}
          {#if premiere}
            <div>
              <dt>{detail.kind === "series" ? "Première diffusion" : "Sortie"}</dt>
              <dd>{premiere}</dd>
            </div>
          {/if}
          {#if detail.criticRating != null}
            <div>
              <dt>Critiques</dt>
              <dd>{detail.criticRating} %</dd>
            </div>
          {/if}
        </dl>
      </div>

      {#if detail.kind === "series"}
        <EpisodeList seriesId={detail.id} {onError} onPlay={play} {starting} />
      {:else if detail.media.length > 0}
        <section class="tech" aria-labelledby="tech-title">
          <h2 id="tech-title">Informations techniques</h2>
          {#each detail.media as media, i (i)}
            <div class="media">
              {#if detail.media.length > 1}
                <h3>{media.name ?? `Version ${i + 1}`}</h3>
              {/if}
              <dl>
                {#if media.video}
                  <div>
                    <dt>Vidéo</dt>
                    <dd>{videoLabel(media.video)}</dd>
                  </div>
                {/if}
                <div>
                  <dt>Fichier</dt>
                  <dd>
                    {[media.container?.toUpperCase(), formatSize(media.sizeBytes), formatBitrate(media.bitrate)]
                      .filter(Boolean)
                      .join(" · ") || "Inconnu"}
                  </dd>
                </div>
                <div>
                  <dt>Audio</dt>
                  <dd>
                    {#if media.audio.length === 0}
                      Aucune piste
                    {:else}
                      <ul>
                        {#each media.audio as a, j (j)}
                          <li>
                            {audioLabel(a)}{#if a.isDefault}<span class="tag">par défaut</span>{/if}
                          </li>
                        {/each}
                      </ul>
                    {/if}
                  </dd>
                </div>
                <div>
                  <dt>Sous-titres</dt>
                  <dd>
                    {#if media.subtitles.length === 0}
                      Aucun
                    {:else}
                      <ul>
                        {#each media.subtitles as s, j (j)}
                          <li>
                            {subtitleLabel(s)}{#if s.isDefault}<span class="tag">par défaut</span>{/if}
                          </li>
                        {/each}
                      </ul>
                    {/if}
                  </dd>
                </div>
              </dl>
            </div>
          {/each}
          <p class="note">
            Décrit le fichier sur le serveur. La lecture directe ou le transcodage seront
            négociés avec le serveur au lancement de la lecture.
          </p>
        </section>
      {/if}
    {:else}
      <div class="sk-text" aria-hidden="true">
        <div></div>
        <div></div>
        <div class="short"></div>
      </div>
    {/if}
  </div>
  {#if playError}
    <div class="play-error" role="alert">
      <div>
        <p class="state-title">Lecture impossible</p>
        <p>{playError.message}</p>
        {#if errorHint(playError)}<p class="hint">{errorHint(playError)}</p>{/if}
      </div>
      <button type="button" class="secondary" onclick={() => (playError = null)}>Fermer</button>
    </div>
  {/if}
</section>

<style>
  .detail {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    background: var(--color-bg);
    outline: none;
  }
  .hero {
    position: relative;
    min-height: min(62vh, 640px);
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
    animation: fade-in var(--motion-base) ease-out;
  }
  @keyframes fade-in {
    from {
      opacity: 0;
    }
  }
  .scrim {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(90deg, rgb(11 13 17 / 92%) 0%, rgb(11 13 17 / 55%) 45%, rgb(11 13 17 / 10%) 100%),
      linear-gradient(0deg, var(--color-bg) 0%, rgb(11 13 17 / 0%) 45%);
  }
  .topbar {
    position: relative;
    padding: var(--space-4) clamp(var(--space-4), 3vw, 48px);
  }
  .back {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-2) var(--space-3) var(--space-2) var(--space-2);
    border: 0;
    border-radius: 999px;
    background: rgb(11 13 17 / 60%);
    color: var(--color-text);
    font: inherit;
    cursor: pointer;
    backdrop-filter: blur(8px);
  }
  .back:hover {
    background: var(--color-elevated);
  }
  .back svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .hero-content {
    position: relative;
    display: flex;
    align-items: flex-end;
    gap: clamp(var(--space-4), 3vw, 40px);
    padding: var(--space-6) clamp(var(--space-4), 3vw, 48px) var(--space-6);
  }
  .poster {
    flex: none;
    width: clamp(140px, 16vw, 220px);
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
    box-shadow: 0 18px 48px rgb(0 0 0 / 55%);
  }
  .poster img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .headline {
    min-width: 0;
    max-width: 760px;
  }
  h1 {
    margin: 0;
    font-size: clamp(28px, 3.4vw, 44px);
    line-height: 1.1;
    letter-spacing: -0.015em;
    outline: none;
  }
  /* Focused by script for screen readers only; it is not in the tab order. */
  h1:focus-visible {
    outline: none;
  }
  .logo {
    display: block;
    max-width: min(420px, 100%);
    max-height: 140px;
    object-fit: contain;
    object-position: left bottom;
  }
  .original {
    margin: var(--space-2) 0 0;
    color: var(--color-text-secondary);
  }
  .meta {
    margin: var(--space-3) 0 0;
    color: var(--color-text-secondary);
  }
  .rating {
    color: var(--color-text);
  }
  .rating svg {
    width: 14px;
    height: 14px;
    vertical-align: -2px;
    margin-right: 3px;
    fill: #f2c94c;
  }
  .genres {
    list-style: none;
    margin: var(--space-3) 0 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .genres li {
    padding: 2px var(--space-3);
    border-radius: 999px;
    background: rgb(255 255 255 / 8%);
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .status {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }
  .status:empty {
    display: none;
  }
  .pill {
    padding: var(--space-1) var(--space-3);
    border-radius: 999px;
    background: var(--color-accent);
    color: #04121b;
    font-size: 13px;
    font-weight: 600;
  }
  .pill.done {
    background: var(--color-success);
    color: #062014;
  }
  .resume {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .bar {
    width: 120px;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 16%);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: var(--color-accent);
  }
  .body {
    padding: var(--space-2) clamp(var(--space-4), 3vw, 48px) 64px;
  }
  .columns {
    display: grid;
    grid-template-columns: minmax(0, 3fr) minmax(220px, 2fr);
    gap: clamp(var(--space-6), 4vw, 64px);
  }
  .tagline {
    margin: 0 0 var(--space-2);
    font-style: italic;
    color: var(--color-text);
  }
  .overview {
    margin: 0;
    max-width: 72ch;
    line-height: 1.6;
    color: var(--color-text-secondary);
  }
  .muted {
    color: var(--color-text-muted);
  }
  dl {
    margin: 0;
    display: grid;
    gap: var(--space-3);
  }
  dt {
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--color-text-muted);
  }
  dd {
    margin: 2px 0 0;
    color: var(--color-text-secondary);
    line-height: 1.5;
  }
  dd ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .tech {
    margin-top: 40px;
    max-width: 900px;
  }
  h2 {
    margin: 0 0 var(--space-4);
    font-size: 20px;
  }
  h3 {
    margin: var(--space-4) 0 var(--space-2);
    font-size: 15px;
  }
  .media dl {
    grid-template-columns: max-content 1fr;
    column-gap: var(--space-6);
  }
  .media dl > div {
    display: contents;
  }
  .media dd {
    margin: 0;
  }
  .media dt {
    padding-top: 3px;
  }
  .tag {
    margin-left: var(--space-2);
    padding: 0 6px;
    border-radius: 4px;
    background: var(--color-elevated);
    font-size: 11px;
    color: var(--color-text-muted);
  }
  .note {
    margin: var(--space-4) 0 0;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  .state {
    max-width: 520px;
    color: var(--color-text-secondary);
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
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-4);
  }
  .primary,
  .secondary {
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    cursor: pointer;
  }
  .play-actions {
    margin-top: var(--space-4);
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .play {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-6);
  }
  .play svg {
    width: 16px;
    height: 16px;
    fill: currentColor;
  }
  .from-start {
    padding: var(--space-2) var(--space-4);
  }
  .play[aria-disabled="true"],
  .from-start[aria-disabled="true"] {
    opacity: 0.7;
    cursor: progress;
  }
  .play-error {
    position: sticky;
    bottom: var(--space-4);
    margin: var(--space-4) auto 0;
    width: min(640px, calc(100% - 2 * var(--space-4)));
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-4);
    padding: var(--space-4);
    border-radius: var(--radius-md);
    background: var(--color-elevated);
    border-left: 3px solid var(--color-danger);
    box-shadow: 0 8px 24px rgb(0 0 0 / 40%);
  }
  .play-error p {
    margin: 0;
  }
  .play-error .state-title {
    font-weight: 600;
    margin-bottom: var(--space-1);
  }
  .primary {
    background: var(--color-accent);
    color: #04121b;
    font-weight: 600;
  }
  .secondary {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .sk-meta,
  .sk-text div {
    height: 14px;
    border-radius: 7px;
    background: var(--color-surface);
    animation: pulse 1.4s ease-in-out infinite;
  }
  .sk-meta {
    width: 240px;
    margin-top: var(--space-4);
  }
  .sk-text {
    display: grid;
    gap: var(--space-3);
    max-width: 640px;
  }
  .sk-text .short {
    width: 50%;
  }
  @keyframes pulse {
    50% {
      opacity: 0.55;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .backdrop,
    .sk-meta,
    .sk-text div {
      animation: none;
    }
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
    .hero {
      min-height: 0;
    }
    .poster {
      display: none;
    }
    .columns {
      grid-template-columns: 1fr;
    }
    .media dl {
      grid-template-columns: 1fr;
    }
  }
</style>
