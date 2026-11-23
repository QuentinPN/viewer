<script lang="ts">
// Detail page of a film, series or episode (DESIGN.md, Film detail / Series).
// Shown over the library grid, which stays mounted so its scroll position
// survives the return. A series opens its episodes as a nested page over itself.
import { onMount, tick, untrack } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  Episode,
  EpisodeLink,
  ItemDetail,
  MediaItem,
  PlaybackStart,
  PlaybackStopped,
} from "../emby/types";
import { stremioApi } from "../stremio/api";
import { sourcesReopen } from "../stremio/reopen.svelte";
import SourcesPanel from "../stremio/SourcesPanel.svelte";
import { offersSources } from "../stremio/sources";
import type { StremioState } from "../stremio/types";
import EpisodeList from "./EpisodeList.svelte";
import { changeFavorite } from "./favorite";
import {
  audioLabel,
  episodeItem,
  episodeLinkCode,
  episodePosition,
  formatBitrate,
  formatDate,
  formatSize,
  metaParts,
  ratingLabel,
  resumeLabel,
  subtitleLabel,
  videoLabel,
} from "./format";
import EpisodePage from "./ItemDetail.svelte";
import {
  collectionPage,
  type DetailPage,
  initials,
  namedPage,
  personPage,
} from "./pages";
import TitleRow from "./TitleRow.svelte";
import {
  loadVersion,
  saveVersion,
  versionChoices,
  versionKey,
} from "./versions";
import { changePlayed, playedAction } from "./watched";

interface Props {
  /** Grid card: title and poster are shown at once while the detail loads. */
  item: MediaItem;
  /** Bumped when the connection comes back. */
  refresh: number;
  onClose: () => void;
  /** Returns the error; a rejected token also ends the session. */
  onError: (error: EmbyError) => EmbyError;
  /** Playback started by the server negotiation: show the player. */
  onPlay: (start: PlaybackStart) => void;
  /** The server took a favorite change: the home row "Favoris" reloads. */
  onFavoriteChange?: (itemId: string, favorite: boolean) => void;
  /** Episode page: shows a neighbour episode instead. */
  onNavigate?: (link: EpisodeLink, via: Direction) => void;
  /** Episode page reached by a previous/next button: the focus goes back to it. */
  focusOnLoad?: Direction | null;
  /** Episode page: called with each loaded detail. */
  onLoaded?: (detail: ItemDetail) => void;
  /** Error state: where the return button goes, when not the default. */
  backLabel?: string | undefined;
  /** Server and user the chosen version is remembered for; none: not kept. */
  versionScope?: string | undefined;
  /** Opens a person, another title or a list over this page. */
  onOpenPage?: ((page: DetailPage) => void) | undefined;
}

type Direction = "previous" | "next";

let {
  item,
  refresh,
  onClose,
  onError,
  onPlay,
  onFavoriteChange,
  onNavigate,
  focusOnLoad = null,
  onLoaded,
  backLabel,
  versionScope,
  onOpenPage,
}: Props = $props();

// Only an episode page is nested over another page: ids stay unique.
const isEpisodePage = untrack(() => item.itemType === "Episode");
const titleId = isEpisodePage ? "episode-title" : "detail-title";
const techId = isEpisodePage ? "episode-tech-title" : "tech-title";

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
/** A favorite change is waiting for the server: further clicks are ignored. */
let savingFavorite = $state(false);
let favoriteError = $state<EmbyError | null>(null);
/** A watched change is waiting for the server: further clicks are ignored. */
let savingPlayed = $state(false);
let playedError = $state<EmbyError | null>(null);
let previousButton = $state<HTMLButtonElement | null>(null);
let nextButton = $state<HTMLButtonElement | null>(null);
/** Consumed by the first load only: a later reload leaves the focus alone. */
let pendingFocus = untrack(() => focusOnLoad);
/** Episode page open over this series page, and how it was reached. */
let opened = $state<{ item: MediaItem; via: Direction | null } | null>(null);
/** Season of the episode shown, for the return to the list. */
let openedSeason: string | null = null;
let episodeList = $state<ReturnType<typeof EpisodeList> | null>(null);
/** Cast photos that failed to load, by index. */
let failedPhotos = $state<Set<number>>(new Set());
/** Stremio account state, read once without waiting; null: no button. */
let stremio = $state<StremioState | null>(null);
/** « Autres sources » is open, with the message shown over its list. */
let sources = $state<{ notice: string | null } | null>(null);
let sourcesButton = $state<HTMLButtonElement | null>(null);

const poster = $derived(detail?.poster ?? item.poster);
const meta = $derived(detail ? metaParts(detail) : []);
const rating = $derived(ratingLabel(detail?.communityRating ?? null));
const premiere = $derived(formatDate(detail?.premiereDate ?? null));
const position = $derived(
  detail?.episode ? episodePosition(detail.episode) : null,
);
/** Several versions of this film or episode; empty otherwise. */
const versions = $derived(detail ? versionChoices(detail.media) : []);
/** `MediaSourceId` played from this page; null lets the server pick. */
let version = $state<string | null>(null);
const versionId = isEpisodePage ? "episode-version" : "detail-version";

function storage(): Storage | null {
  try {
    return versionScope ? window.localStorage : null;
  } catch {
    return null;
  }
}

function chooseVersion(id: string) {
  version = id;
  if (detail && versionScope)
    saveVersion(storage(), versionKey(versionScope, detail.id), versions, id);
}

// Back online: read the page again in place (watched state may have changed).
let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(() => {
    playError = null;
    void load();
  });
});

async function load() {
  error = null;
  try {
    const d = await embyApi.itemDetail(item.id);
    if (d.id !== item.id) return;
    detail = d;
    const choices = versionChoices(d.media);
    version = versionScope
      ? loadVersion(storage(), versionKey(versionScope, d.id), choices)
      : (choices.find((c) => c.id === version)?.id ?? choices[0]?.id ?? null);
    onLoaded?.(d);
    if (pendingFocus) {
      const via = pendingFocus;
      pendingFocus = null;
      await tick();
      const button = via === "next" ? nextButton : previousButton;
      if (button) button.focus();
      else heading?.focus({ preventScroll: true });
    }
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
    // The version chosen here is for this page's item, not for a listed episode.
    const source = id === detail?.id && versions.length > 0 ? version : null;
    onPlay(await embyApi.play(id, resume, false, source));
  } catch (e) {
    playError = onError(toEmbyError(e));
  } finally {
    starting = null;
  }
}

/** Plays the trailer file stored with the title. */
async function playTrailer() {
  const d = detail;
  if (!d || starting) return;
  starting = "trailer";
  fromStart = false;
  playError = null;
  try {
    const id = await embyApi.localTrailer(d.id);
    if (!id) {
      playError = {
        kind: "server",
        message: "Bande-annonce introuvable sur le serveur.",
      };
      return;
    }
    onPlay(await embyApi.play(id, false));
  } catch (e) {
    playError = onError(toEmbyError(e));
  } finally {
    starting = null;
  }
}

async function closeSources() {
  sources = null;
  await tick();
  sourcesButton?.focus({ preventScroll: true });
}

/** The panel closes first: the player gives the focus back to its button. */
async function playSource(start: PlaybackStart) {
  await closeSources();
  onPlay(start);
}

// A Stremio stream of this page failed: its list opens again, read anew.
$effect(() => {
  if (!sourcesReopen.request) return;
  untrack(() => {
    if (opened || !section?.checkVisibility()) return;
    const notice = sourcesReopen.take(item.id);
    if (notice !== null) sources = { notice };
  });
});

function openTitle(item: MediaItem) {
  onOpenPage?.({ kind: "item", item, via: null });
}

async function toggleFavorite() {
  const d = detail;
  if (!d || savingFavorite) return;
  savingFavorite = true;
  favoriteError = null;
  try {
    const favorite = await changeFavorite(
      !d.favorite,
      (f) => (d.favorite = f),
      (f) => embyApi.setFavorite(d.id, f),
    );
    onFavoriteChange?.(d.id, favorite);
  } catch (e) {
    favoriteError = onError(toEmbyError(e));
  } finally {
    savingFavorite = false;
  }
}

/**
 * Marks the item played or unplayed. A series or season is read again
 * on the event that follows: whether Emby applies it to the episodes is not
 * documented, so the counts come from the server, not from here.
 */
async function togglePlayed() {
  const d = detail;
  if (!d || savingPlayed) return;
  savingPlayed = true;
  playedError = null;
  const before = d.resume;
  try {
    const state = await changePlayed(
      !d.played,
      (p) => {
        d.played = p;
        d.resume = p ? null : before;
      },
      (p) => embyApi.setPlayed(d.id, p),
    );
    if (d.kind !== "series") d.resume = state.resume;
  } catch (e) {
    playedError = onError(toEmbyError(e));
  } finally {
    savingPlayed = false;
  }
}

/** New watched status and resume point after a playback. */
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

function openEpisode(e: Episode) {
  openedSeason = null;
  opened = { item: episodeItem(e), via: null };
}

function navigate(link: EpisodeLink, via: Direction) {
  openedSeason = null;
  opened = { item: episodeItem(link), via };
}

/** Back to the series, on the episode shown last. */
async function closeEpisode() {
  const id = opened?.item.id;
  const season = openedSeason;
  opened = null;
  if (!id) return;
  await tick();
  await episodeList?.reveal(id, season);
}

/** Hidden under the player or an episode page: keys belong to them then. */
function active() {
  return !opened && !sources && (section?.checkVisibility() ?? false);
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
  stremioApi.status().then(
    (s) => (stremio = s.state),
    () => {},
  );
  const unlisten = embyApi.onPlaybackStopped(onStopped);
  return () => void unlisten.then((stop) => stop());
});
</script>

<svelte:window onkeydown={onKeydown} onmouseup={onMouseup} />

<section
  class="detail"
  class:covered={opened !== null}
  inert={opened !== null}
  aria-labelledby={titleId}
  aria-busy={!detail && !error}
  bind:this={section}
>
  <div class="hero" class:has-backdrop={detail?.backdrop && !backdropFailed}>
    {#if detail?.backdrop && !backdropFailed}
      <img
        class="hero-backdrop"
        src={detail.backdrop.src}
        srcset={detail.backdrop.srcset}
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

    <div class="hero-content">
      <div class="poster">
        {#if poster}
          <img src={poster.src} srcset={poster.srcset} sizes="240px" alt="" decoding="async" />
        {/if}
      </div>

      <div class="headline">
        {#if detail?.episode}
          <p class="series">
            {#if detail.logo && !logoFailed}
              <img
                class="series-logo"
                src={detail.logo.src}
                srcset={detail.logo.srcset}
                sizes="200px"
                alt={detail.episode.seriesName}
                onerror={() => (logoFailed = true)}
              />
            {:else}
              {detail.episode.seriesName}
            {/if}
          </p>
          {#if position}<p class="position">{position}</p>{/if}
        {/if}
        <h1 id={titleId} tabindex="-1" bind:this={heading}>
          {#if detail?.logo && !logoFailed && detail.kind !== "episode"}
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
              {#each detail.genres as genre (genre)}
                {@const page = onOpenPage ? namedPage("genre", genre, detail.kind) : null}
                <li>
                  {#if page}
                    <button
                      type="button"
                      class="chip"
                      title="Voir les titres du genre {genre}"
                      onclick={() => onOpenPage?.(page)}>{genre}</button
                    >
                  {:else}
                    <span class="chip">{genre}</span>
                  {/if}
                </li>
              {/each}
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

          <div class="play-actions">
            {#if detail.kind !== "series"}
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
            {/if}
            <button
              type="button"
              class="secondary watched"
              aria-pressed={detail.played}
              aria-disabled={savingPlayed}
              title={playedAction(detail.played)}
              onclick={togglePlayed}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 8.5 6.5 12 13 4.5" /></svg>
              Vu
            </button>
            {#if detail.kind !== "episode"}
            <button
              type="button"
              class="secondary favorite"
              aria-pressed={detail.favorite}
              aria-disabled={savingFavorite}
              title={detail.favorite ? "Retirer des favoris" : "Ajouter aux favoris"}
              onclick={toggleFavorite}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"
                ><path d="m8 1.8 1.9 3.9 4.3.6-3.1 3 .7 4.3L8 11.6l-3.8 2 .7-4.3-3.1-3 4.3-.6z" /></svg
              >
              Favori
            </button>
            {/if}
            {#if detail.hasLocalTrailer}
              <button
                type="button"
                class="secondary trailer"
                aria-disabled={starting !== null}
                aria-busy={starting === "trailer"}
                title="Lire la bande-annonce"
                onclick={playTrailer}
              >
                <svg viewBox="0 0 16 16" aria-hidden="true"
                  ><rect x="1.5" y="3" width="13" height="10" rx="1.5" /><path d="m6.5 6 3.5 2-3.5 2z" /></svg
                >
                {starting === "trailer" ? "Ouverture…" : "Bande-annonce"}
              </button>
            {/if}
            {#if offersSources(stremio, detail.kind)}
              <button
                type="button"
                class="secondary"
                aria-haspopup="dialog"
                bind:this={sourcesButton}
                onclick={() => (sources = { notice: null })}
              >
                Autres sources
              </button>
            {/if}
          </div>
          {#if versions.length > 0 && version}
            <div class="version">
              <label for={versionId}>Version</label>
              <select
                id={versionId}
                value={version}
                onchange={(e) => chooseVersion(e.currentTarget.value)}
              >
                {#each versions as v (v.id)}
                  <option value={v.id}>{v.label}</option>
                {/each}
              </select>
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
          <button type="button" class="secondary" onclick={onClose}
            >{backLabel ??
              (isEpisodePage ? "Retour à la série" : "Retour à la bibliothèque")}</button
          >
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
          {#if detail.writers.length > 0}
            <div>
              <dt>Scénario</dt>
              <dd>{detail.writers.join(", ")}</dd>
            </div>
          {/if}
          {#if detail.studios.length > 0}
            <div>
              <dt>{detail.kind === "series" ? "Diffusion" : "Studio"}</dt>
              <dd>
                <ul class="studios">
                  {#each detail.studios as studio (studio)}
                    {@const page = onOpenPage ? namedPage("studio", studio, detail.kind) : null}
                    <li>
                      {#if page}
                        <button
                          type="button"
                          class="link"
                          title="Voir les titres de {studio}"
                          onclick={() => onOpenPage?.(page)}>{studio}</button
                        >
                      {:else}
                        {studio}
                      {/if}
                    </li>
                  {/each}
                </ul>
              </dd>
            </div>
          {/if}
          {#if premiere && detail.kind !== "episode"}
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

      {#if detail.cast.length > 0}
        <section class="cast" aria-labelledby="{titleId}-cast">
          <h2 id="{titleId}-cast">{detail.kind === "episode" ? "Invités" : "Distribution"}</h2>
          <ul>
            {#each detail.cast as person, i (i)}
              {@const page = onOpenPage ? personPage(person) : null}
              <li>
                {#snippet face()}
                  <span class="face" aria-hidden="true">
                    {#if person.photo && !failedPhotos.has(i)}
                      <img
                        src={person.photo.src}
                        srcset={person.photo.srcset}
                        sizes="88px"
                        alt=""
                        loading="lazy"
                        decoding="async"
                        onerror={() => (failedPhotos = new Set(failedPhotos).add(i))}
                      />
                    {:else}
                      {initials(person.name)}
                    {/if}
                  </span>
                  <span class="person-name">{person.name}</span>
                  {#if person.role}<span class="person-role">{person.role}</span>{/if}
                {/snippet}
                {#if page}
                  <button
                    type="button"
                    class="person"
                    title="Voir les titres de {person.name}"
                    onclick={() => onOpenPage?.(page)}>{@render face()}</button
                  >
                {:else}
                  <div class="person">{@render face()}</div>
                {/if}
              </li>
            {/each}
          </ul>
        </section>
      {/if}

      {#if onOpenPage && detail.kind !== "episode"}
        {#each detail.collections as collection (collection.id)}
          <TitleRow
            source={{ kind: "collection", id: collection.id }}
            heading="Collection : {collection.name}"
            rowId="{titleId}-collection-{collection.id}"
            exclude={detail.id}
            {refresh}
            {onError}
            onOpen={openTitle}
            onMore={() => onOpenPage?.(collectionPage(collection))}
          />
        {/each}
      {/if}

      {#if detail.kind === "series"}
        <EpisodeList
          bind:this={episodeList}
          seriesId={detail.id}
          {refresh}
          {onError}
          onPlay={play}
          onOpen={openEpisode}
          {starting}
        />
      {:else if detail.media.length > 0}
        <section class="tech" aria-labelledby={techId}>
          <h2 id={techId}>Informations techniques</h2>
          {#each detail.media as media, i (i)}
            <div class="media">
              {#if detail.media.length > 1}
                <h3>
                  {media.name ?? `Version ${i + 1}`}{#if versions.length > 0 && media.id === version}<span
                      class="tag">choisie</span
                    >{/if}
                </h3>
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
            Décrit le fichier sur le serveur. Au lancement, le serveur choisit entre
            lecture directe et transcodage selon le fichier et le débit autorisé.
          </p>
        </section>
      {/if}

      {#if detail.episode && (detail.episode.previous || detail.episode.next)}
        {@const ep = detail.episode}
        <nav class="adjacent" aria-label="Épisodes voisins">
          {#if ep.previous}
            {@const link = ep.previous}
            <button
              type="button"
              class="previous"
              bind:this={previousButton}
              onclick={() => onNavigate?.(link, "previous")}
            >
              <span class="direction">Épisode précédent</span>
              <span class="target">{[episodeLinkCode(link), link.name].filter(Boolean).join(" · ")}</span>
            </button>
          {/if}
          {#if ep.next}
            {@const link = ep.next}
            <button
              type="button"
              class="next"
              bind:this={nextButton}
              onclick={() => onNavigate?.(link, "next")}
            >
              <span class="direction">Épisode suivant</span>
              <span class="target">{[episodeLinkCode(link), link.name].filter(Boolean).join(" · ")}</span>
            </button>
          {/if}
        </nav>
      {/if}

      {#if onOpenPage && detail.kind !== "episode"}
        <TitleRow
          source={{ kind: "similar", id: detail.id }}
          heading="Titres similaires"
          rowId="{titleId}-similar"
          {refresh}
          {onError}
          onOpen={openTitle}
        />
      {/if}
    {:else}
      <div class="sk-text" aria-hidden="true">
        <div></div>
        <div></div>
        <div class="short"></div>
      </div>
    {/if}
  </div>
  {#if favoriteError}
    <div class="play-error" role="alert">
      <div>
        <p class="state-title">Favori non modifié</p>
        <p>{favoriteError.message}</p>
        {#if errorHint(favoriteError)}<p class="hint">{errorHint(favoriteError)}</p>{/if}
      </div>
      <button type="button" class="secondary" onclick={() => (favoriteError = null)}>Fermer</button>
    </div>
  {/if}
  {#if playedError}
    <div class="play-error" role="alert">
      <div>
        <p class="state-title">Statut « vu » non modifié</p>
        <p>{playedError.message}</p>
        {#if errorHint(playedError)}<p class="hint">{errorHint(playedError)}</p>{/if}
      </div>
      <button type="button" class="secondary" onclick={() => (playedError = null)}>Fermer</button>
    </div>
  {/if}
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

{#if sources}
  <SourcesPanel
    itemId={detail?.id ?? item.id}
    notice={sources.notice}
    onClose={() => void closeSources()}
    onPlay={(start) => void playSource(start)}
  />
{/if}

{#if opened}
  {#key opened.item.id}
    <EpisodePage
      item={opened.item}
      {refresh}
      onClose={closeEpisode}
      {onError}
      {onPlay}
      onNavigate={navigate}
      focusOnLoad={opened.via}
      onLoaded={(d) => (openedSeason = d.episode?.seasonId ?? null)}
      {versionScope}
      {onOpenPage}
    />
  {/key}
{/if}

<style>
  .trailer svg rect,
  .trailer svg path {
    fill: none;
    stroke: currentColor;
    stroke-width: 1.4;
    stroke-linejoin: round;
  }
  .detail {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    --gutter: clamp(var(--space-4), 5vw, 80px);
    background: var(--color-bg);
    outline: none;
  }
  /* Under an episode page: kept with its scroll position, not painted. */
  .covered {
    visibility: hidden;
  }
  .hero {
    position: relative;
    min-height: clamp(420px, 72vh, 760px);
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    overflow: hidden;
  }
  .hero-content {
    position: relative;
    display: flex;
    align-items: flex-end;
    gap: clamp(var(--space-4), 3vw, 40px);
    padding: var(--space-6) var(--gutter) 40px;
  }
  .has-backdrop .poster {
    display: none;
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
    max-width: 680px;
  }
  h1 {
    margin: 0;
    font-size: clamp(36px, 4.4vw, 64px);
    font-weight: 700;
    line-height: 1.05;
    outline: none;
  }
  /* Focused by script for screen readers only; it is not in the tab order. */
  h1:focus-visible {
    outline: none;
  }
  .logo {
    display: block;
    max-width: min(460px, 100%);
    max-height: clamp(64px, min(12vw, 18vh), 160px);
    object-fit: contain;
    object-position: left bottom;
  }
  .series {
    margin: 0 0 var(--space-2);
    font-size: 15px;
    font-weight: 600;
    color: var(--color-text-secondary);
  }
  .series-logo {
    display: block;
    max-width: min(200px, 60%);
    max-height: 56px;
    object-fit: contain;
    object-position: left bottom;
  }
  .position {
    margin: 0 0 var(--space-1);
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--color-accent);
  }
  .adjacent {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
    margin-top: 40px;
    max-width: 900px;
  }
  .adjacent button {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    padding: var(--space-3) var(--space-4);
    border: 0;
    border-radius: var(--radius-md);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    text-align: start;
    cursor: pointer;
    transition: background var(--motion-fast) ease-out;
  }
  .adjacent button:hover {
    background: var(--fill-glass-hover);
  }
  .adjacent .next {
    grid-column: 2;
    align-items: flex-end;
    text-align: end;
  }
  .direction {
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .target {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .original {
    margin: var(--space-2) 0 0;
    color: var(--color-text-secondary);
  }
  .meta {
    margin: var(--space-3) 0 0;
    font-size: 15px;
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
  .chip {
    display: inline-block;
    padding: 2px var(--space-3);
    border: none;
    border-radius: 999px;
    background: var(--fill-glass);
    font: inherit;
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  button.chip {
    cursor: pointer;
    transition:
      background var(--motion-fast),
      color var(--motion-fast);
  }
  button.chip:hover {
    background: var(--fill-glass-hover);
    color: var(--color-text);
  }
  .studios {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 0 var(--space-3);
  }
  .link {
    padding: 0;
    border: none;
    background: none;
    color: inherit;
    font: inherit;
    text-decoration: underline;
    text-decoration-color: var(--separator);
    text-underline-offset: 3px;
    cursor: pointer;
  }
  .link:hover {
    text-decoration-color: currentColor;
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
    border-radius: var(--radius-pill);
    background: var(--fill-glass);
    color: var(--color-text);
    font-size: 13px;
    font-weight: 600;
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
    background: rgb(255 255 255 / 25%);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: #fff;
  }
  .body {
    padding: var(--space-2) var(--gutter) 64px;
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
    font-size: 17px;
    line-height: 1.5;
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
    font-size: 13px;
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
  .cast {
    margin-top: 40px;
  }
  .cast ul {
    list-style: none;
    margin: 0;
    padding: 4px 4px 8px;
    display: flex;
    gap: var(--space-4);
    overflow-x: auto;
    scrollbar-width: thin;
  }
  .cast li {
    flex: none;
    width: 112px;
  }
  .person {
    width: 100%;
    display: grid;
    justify-items: center;
    gap: 2px;
    padding: var(--space-2) 0;
    border: none;
    border-radius: var(--radius-md);
    background: none;
    color: inherit;
    font: inherit;
    text-align: center;
  }
  button.person {
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  button.person:hover {
    background: var(--fill-glass);
  }
  .face {
    width: 88px;
    height: 88px;
    margin-bottom: var(--space-2);
    border-radius: 50%;
    overflow: hidden;
    display: grid;
    place-items: center;
    background: var(--color-surface);
    color: var(--color-text-secondary);
    font-size: 24px;
    font-weight: 600;
  }
  .face img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .person-name {
    font-size: 14px;
    font-weight: 600;
    line-height: 1.25;
  }
  .person-role {
    font-size: 13px;
    color: var(--color-text-secondary);
    line-height: 1.25;
  }
  .tech {
    margin-top: 40px;
    max-width: 900px;
    padding: 22px 24px;
    border: 1px solid var(--separator);
    border-radius: var(--radius-lg);
    background: var(--material);
  }
  h2 {
    margin: 0 0 var(--space-4);
    font-size: 22px;
    font-weight: 600;
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
    margin-top: 22px;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-3);
  }
  .play-actions button {
    height: 48px;
    padding: 0 22px;
    border-radius: 12px;
    font-size: 17px;
    font-weight: 600;
  }
  .play {
    display: inline-flex;
    align-items: center;
    gap: 10px;
  }
  /* Glass native select, as in the grid toolbar: keyboard and screen readers kept. */
  .version {
    margin-top: var(--space-4);
    display: grid;
    gap: 6px;
    max-width: 100%;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  .version select {
    appearance: none;
    width: fit-content;
    max-width: min(560px, 100%);
    height: 38px;
    padding: 0 36px 0 14px;
    border: 0;
    border-radius: var(--radius-sm);
    background:
      url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23f5f5f7' stroke-width='2.5' stroke-linecap='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E")
        no-repeat right 14px center,
      var(--fill-glass);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    font-weight: 500;
    text-overflow: ellipsis;
    cursor: pointer;
    transition: background-color var(--motion-fast);
  }
  .version select:hover {
    background-color: var(--fill-glass-hover);
  }
  .version select option {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .version select:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .play-actions .play {
    padding: 0 26px;
    box-shadow: 0 12px 32px rgb(0 0 0 / 45%);
  }
  .play svg {
    width: 16px;
    height: 16px;
    fill: currentColor;
  }
  .favorite,
  .watched {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .watched svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
    opacity: 0.55;
  }
  .watched[aria-pressed="true"] svg {
    stroke: var(--color-accent);
    stroke-width: 2.2;
    opacity: 1;
  }
  .watched[aria-disabled="true"] {
    cursor: progress;
  }
  .favorite svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.4;
    stroke-linejoin: round;
  }
  .favorite[aria-pressed="true"] svg {
    fill: #f2c94c;
    stroke: #f2c94c;
  }
  .favorite[aria-disabled="true"] {
    cursor: progress;
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
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-weight: 600;
  }
  .secondary {
    background: var(--fill-glass);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    transition: background var(--motion-fast);
  }
  .secondary:hover {
    background: var(--fill-glass-hover);
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
    .adjacent {
      grid-template-columns: 1fr;
    }
    .adjacent .next {
      grid-column: 1;
    }
  }
</style>
