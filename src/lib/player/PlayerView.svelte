<script lang="ts">
// Player of a playback started from Emby (ADR 0017): title at the top, controls
// that fade after a few seconds without activity, audio, subtitles and
// diagnostic panels. The video is the native surface under this transparent page.
// At the end of an episode, a card offers the next one (ADR 0018).
import { onDestroy, onMount, tick } from "svelte";
import { embyApi } from "../emby/api";
import type { EpisodeLink, PlaybackStart } from "../emby/types";
import { playerApi } from "./api";
import {
  formatRemaining,
  formatTime,
  playbackHeading,
  playMethodLabel,
  trackLabel,
} from "./format";
import { mapPlayerKey } from "./keyboard";
import type {
  EndReason,
  PlayerCommand,
  PlayerDiagnostics,
  PlayerEvent,
  Track,
} from "./types";
import {
  isEpisode,
  nextTitle,
  startCountdown,
  UP_NEXT_SECONDS,
} from "./upnext";
import {
  effectiveVolume,
  readVolume,
  saveVolume,
  setLevel,
  stepVolume,
  toggleMute,
  type VolumeSetting,
} from "./volume";

interface Props {
  playback: PlaybackStart;
  onExit: () => void;
  /**
   * The playback ended on an error or well before the end (the stream broke,
   * ADR 0012): the player closes and the app offers to resume.
   */
  onInterrupted: (itemId: string) => void;
  /**
   * Starts the next episode in the open engine; resolves with an error message
   * when it could not start, `null` once the app shows the new playback.
   */
  onPlayNext: (itemId: string) => Promise<string | null>;
}

let { playback, onExit, onInterrupted, onPlayNext }: Props = $props();

/** Same margin as Rust's early end of file (`EARLY_EOF_TICKS`). */
const EARLY_EOF_SECONDS = 30;
/** Controls fade after this long without pointer or key. */
const HIDE_AFTER_MS = 3000;
const SKIP_SECONDS = 10;

type Panel = "audio" | "subs" | "info";

let active = $state(false);
let paused = $state(false);
let buffering = $state(false);
let position = $state(0);
let duration = $state(0);
let tracks = $state<Track[]>([]);
let diag = $state<PlayerDiagnostics | null>(null);
let error = $state<string | null>(null);
let fullscreen = $state(false);
let scrubbing = $state(false);
let panel = $state<Panel | null>(null);
let awake = $state(true);
let overChrome = $state(false);
let volume = $state<VolumeSetting>(readVolume(storage()));

let audioButton = $state<HTMLButtonElement | null>(null);
let subsButton = $state<HTMLButtonElement | null>(null);
let infoButton = $state<HTMLButtonElement | null>(null);
let panelElement = $state<HTMLElement | null>(null);
/** Next episode of the series, looked up while this one plays. */
let nextEpisode = $state<EpisodeLink | null>(null);
/** Seconds left on the "Épisode suivant" card, shown once the episode ended. */
let upNextLeft = $state<number | null>(null);
/** Next episode being opened, until the app shows its playback. */
let launching = $state<EpisodeLink | null>(null);
let playNowButton = $state<HTMLButtonElement | null>(null);
let stopCountdown: (() => void) | undefined;

const heading = $derived(
  playbackHeading(
    launching ? nextTitle(launching, playback.title) : playback.title,
  ),
);
const upNext = $derived(
  upNextLeft !== null && nextEpisode
    ? playbackHeading(nextTitle(nextEpisode, playback.title))
    : null,
);
const decision = $derived(playMethodLabel(playback));
const audioTracks = $derived(tracks.filter((t) => t.kind === "audio"));
const subTracks = $derived(tracks.filter((t) => t.kind === "sub"));
const subsOff = $derived(!subTracks.some((t) => t.selected));
const progress = $derived(
  duration > 0 ? Math.min(100, (position / duration) * 100) : 0,
);
const shownVolume = $derived(effectiveVolume(volume));
const chromeVisible = $derived(
  !active ||
    awake ||
    paused ||
    buffering ||
    scrubbing ||
    overChrome ||
    panel !== null ||
    upNext !== null ||
    error !== null,
);

// Each episode looks up its successor; an answer for an older one is dropped.
$effect(() => {
  const { itemId, title } = playback;
  launching = null;
  nextEpisode = null;
  if (!isEpisode(title)) return;
  embyApi
    .itemDetail(itemId)
    .then((detail) => {
      if (playback.itemId === itemId)
        nextEpisode = detail.episode?.next ?? null;
    })
    .catch(() => {
      // No card then: the end closes the player as for a film.
    });
});

let unlisten: (() => void) | undefined;
let diagTimer: ReturnType<typeof setInterval> | undefined;
let hideTimer: ReturnType<typeof setTimeout> | undefined;

/** `localStorage` may be missing or refused: the volume is then not kept. */
function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** Shows the controls and restarts the countdown that hides them. */
function wake() {
  awake = true;
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => {
    awake = false;
  }, HIDE_AFTER_MS);
}

function onEvent(ev: PlayerEvent) {
  switch (ev.type) {
    case "fileLoaded":
      active = true;
      error = null;
      void applyVolume();
      wake();
      break;
    case "timePos":
      if (!scrubbing) position = ev.seconds;
      break;
    case "duration":
      duration = ev.seconds;
      break;
    case "paused":
      paused = ev.paused;
      break;
    case "buffering":
      buffering = ev.active;
      break;
    case "tracks":
      tracks = ev.tracks;
      break;
    case "endFile":
      if (interruptedBy(ev.reason)) {
        void close(() => onInterrupted(playback.itemId));
      } else if (ev.reason === "error")
        error = `Lecture impossible : ${ev.error ?? "erreur inconnue"}`;
      else if (ev.reason === "eof") {
        if (active && nextEpisode) void offerNext();
        else void close();
      }
      break;
    case "shutdown":
      active = false;
      break;
  }
}

onMount(async () => {
  unlisten = await playerApi.onEvent(onEvent);
  diagTimer = setInterval(async () => {
    if (!active) return;
    try {
      diag = await playerApi.diagnostics();
      // Engine state is authoritative: resync when an event was missed.
      if (diag.paused != null) paused = diag.paused;
    } catch {
      diag = null;
    }
  }, 1000);
  // `emby_play` loaded the file before this view subscribed: the first events
  // may be gone, so read the running engine once.
  try {
    diag = await playerApi.diagnostics();
    if (diag.duration != null) duration = diag.duration;
    if (diag.timePos != null) position = diag.timePos;
    active = true;
    tracks = await playerApi.tracks();
    await applyVolume();
  } catch {
    // Not started yet: `fileLoaded` will come.
  }
  wake();
});

onDestroy(() => {
  stopCountdown?.();
  unlisten?.();
  clearInterval(diagTimer);
  clearTimeout(hideTimer);
});

async function run(command: PlayerCommand) {
  try {
    await playerApi.command(command);
  } catch (e) {
    error = String(e);
  }
}

function applyVolume() {
  return run({ type: "setVolume", percent: effectiveVolume(volume) });
}

function changeVolume(next: VolumeSetting) {
  volume = next;
  saveVolume(storage(), next);
  if (active) void applyVolume();
}

/** An error once the file played, or an end of file far from the duration. */
function interruptedBy(reason: EndReason): boolean {
  if (!active) return false;
  if (reason === "error") return true;
  return (
    reason === "eof" && duration > 0 && duration - position > EARLY_EOF_SECONDS
  );
}

/** The episode ended: the card counts down, then the next one starts. */
async function offerNext() {
  panel = null;
  upNextLeft = UP_NEXT_SECONDS;
  stopCountdown = startCountdown(
    UP_NEXT_SECONDS,
    (left) => (upNextLeft = left),
    () => void playNext(),
  );
  await tick();
  playNowButton?.focus();
}

function dismissNext() {
  stopCountdown?.();
  upNextLeft = null;
}

/**
 * Opens the next episode in the same engine and view: the event subscription,
 * fullscreen and volume stay; the opening screen shows its title meanwhile.
 */
async function playNext() {
  const next = nextEpisode;
  if (!next || launching) return;
  dismissNext();
  launching = next;
  active = false;
  error = null;
  position = 0;
  duration = 0;
  paused = false;
  buffering = false;
  tracks = [];
  diag = null;
  const failure = await onPlayNext(next.id);
  if (failure) error = failure;
}

async function close(then: () => void = onExit) {
  dismissNext();
  if (fullscreen) await toggleFullscreen(false).catch(() => {});
  await playerApi.close();
  active = false;
  tracks = [];
  diag = null;
  then();
}

async function toggleFullscreen(force?: boolean) {
  fullscreen = await playerApi.setFullscreen(force ?? !fullscreen);
}

function toggleButton(which: Panel) {
  return which === "audio"
    ? audioButton
    : which === "subs"
      ? subsButton
      : infoButton;
}

async function openPanel(which: Panel) {
  if (panel === which) {
    closePanel();
    return;
  }
  panel = which;
  await tick();
  const target =
    panelElement?.querySelector<HTMLElement>("input:checked") ??
    panelElement?.querySelector<HTMLElement>("input, [tabindex]");
  target?.focus();
}

function closePanel() {
  if (!panel) return;
  const button = toggleButton(panel);
  panel = null;
  button?.focus();
}

function onKeydown(e: KeyboardEvent) {
  wake();
  if (e.ctrlKey || e.altKey || e.metaKey) return;
  const target = e.target as HTMLElement | null;
  // On the card, Space and Enter belong to its buttons; Escape cancels.
  if (upNext) {
    if (e.key === "Escape") {
      e.preventDefault();
      void close();
    }
    return;
  }
  if (e.key === "Escape" && panel) {
    e.preventDefault();
    closePanel();
    return;
  }
  // In a panel, Space picks a track and the arrows move between tracks.
  const inRadio = target instanceof HTMLInputElement && target.type === "radio";
  if (inRadio && (e.key === " " || e.key.startsWith("Arrow"))) return;
  const inTextField =
    target instanceof HTMLInputElement && target.type === "text";
  const action = mapPlayerKey(e.key, { inTextField, shift: e.shiftKey });
  if (!action || (!active && action.kind !== "exitFullscreen")) return;
  e.preventDefault();
  switch (action.kind) {
    case "command":
      void run(action.command);
      break;
    case "toggleFullscreen":
      void toggleFullscreen();
      break;
    case "exitFullscreen":
      // Escape outside fullscreen goes back, like on the detail page.
      if (fullscreen) void toggleFullscreen(false);
      else void close();
      break;
    case "toggleMute":
      changeVolume(toggleMute(volume));
      break;
    case "volume":
      changeVolume(stepVolume(volume, action.delta));
      break;
    case "toggleInfo":
      void openPanel("info");
      break;
  }
}

function onSurfaceClick() {
  if (panel) closePanel();
  else void run({ type: "togglePause" });
}

function onSeekInput(e: Event) {
  scrubbing = true;
  position = Number((e.currentTarget as HTMLInputElement).value);
}

function onSeekChange(e: Event) {
  scrubbing = false;
  void run({
    type: "seekAbsolute",
    seconds: Number((e.currentTarget as HTMLInputElement).value),
  });
}

function decoding(d: PlayerDiagnostics): string {
  return d.hwdecCurrent && d.hwdecCurrent !== "no"
    ? `Matériel (${d.hwdecCurrent})`
    : "Logiciel";
}

const joined = (...parts: (string | number | null | undefined)[]) =>
  parts.filter((p) => p != null && p !== "").join(" · ") || "—";
</script>

<svelte:window onkeydown={onKeydown} onpointermove={wake} onpointerdown={wake} />

{#snippet icon(name: string)}
  <svg viewBox="0 0 24 24" aria-hidden="true" class="icon">
    {#if name === "play"}
      <path class="solid" d="M8 5.5v13a1 1 0 0 0 1.5.86l10.4-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z" />
    {:else if name === "pause"}
      <rect class="solid" x="6" y="5" width="4" height="14" rx="1" />
      <rect class="solid" x="14" y="5" width="4" height="14" rx="1" />
    {:else if name === "back"}
      <path d="M15 5l-7 7 7 7" />
    {:else if name === "rewind" || name === "forward"}
      <g transform={name === "forward" ? "translate(24 0) scale(-1 1)" : undefined}>
        <path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" />
        <path d="M6.5 3v4h4" />
      </g>
      <text x="12" y="15.5" text-anchor="middle">10</text>
    {:else if name === "volume" || name === "muted"}
      <path class="solid" d="M4 9.5v5h3.5L12 19V5L7.5 9.5H4z" />
      {#if name === "volume"}
        <path d="M15.5 9a4 4 0 0 1 0 6" />
        <path d="M18 6.5a7.5 7.5 0 0 1 0 11" />
      {:else}
        <path d="M16 9.5l5 5M21 9.5l-5 5" />
      {/if}
    {:else if name === "audio"}
      <path d="M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2" />
    {:else if name === "subs"}
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="M7 12h4M13 12h4M7 15.5h7" />
    {:else if name === "info"}
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5M12 7.6v.2" />
    {:else if name === "fullscreen"}
      <path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" />
    {:else if name === "windowed"}
      <path d="M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5" />
    {/if}
  </svg>
{/snippet}

<div
  class="player"
  class:opening={!active}
  class:ended={upNext !== null}
  class:hidden={!chromeVisible}
>
  {#if !active}
    <main class="opening-panel" aria-busy={!error}>
      {#if heading.kicker}<p class="kicker">{heading.kicker}</p>{/if}
      <h1>{heading.name}</h1>
      {#if error}
        <p class="error" role="alert">{error}</p>
      {:else}
        <p class="muted" role="status">Ouverture du flux…</p>
      {/if}
      <button type="button" class="text-button" onclick={() => close()}>Retour</button>
    </main>
  {:else}
    <button
      type="button"
      class="surface"
      tabindex="-1"
      aria-label={paused ? "Reprendre la lecture" : "Mettre en pause"}
      onclick={onSurfaceClick}
    ></button>

    <header class="top chrome">
      <button type="button" class="round" aria-label="Revenir" title="Revenir (Échap)" onclick={() => close()}>
        {@render icon("back")}
      </button>
      <div class="heading">
        {#if heading.kicker}<p class="kicker">{heading.kicker}</p>{/if}
        <h1>{heading.name}</h1>
      </div>
      <span class="decision" title="Décision du serveur Emby">{decision}</span>
    </header>

    {#if buffering}
      <div class="buffering" role="status">
        <span class="spinner" aria-hidden="true"></span>
        Chargement…
      </div>
    {/if}
    {#if error}<p class="error floating" role="alert">{error}</p>{/if}

    {#if upNext && upNextLeft !== null}
      <div class="up-next" role="dialog" aria-label="Épisode suivant">
        <p class="label">Épisode suivant</p>
        {#if upNext.kicker}<p class="kicker">{upNext.kicker}</p>{/if}
        <h2>{upNext.name}</h2>
        <p class="countdown">Lecture dans {upNextLeft} s</p>
        <span class="gauge" aria-hidden="true">
          <span style:width={`${(upNextLeft / UP_NEXT_SECONDS) * 100}%`}></span>
        </span>
        <div class="actions">
          <button type="button" class="play-now" bind:this={playNowButton} onclick={() => playNext()}>
            {@render icon("play")}
            Lire maintenant
          </button>
          <button type="button" class="text-button" onclick={() => close()}>Annuler</button>
        </div>
      </div>
    {:else}
    <section
      class="bottom chrome"
      aria-label="Commandes de lecture"
      onpointerenter={() => (overChrome = true)}
      onpointerleave={() => (overChrome = false)}
    >
      {#if panel}
        <div
          class="panel"
          class:info={panel === "info"}
          role="dialog"
          aria-label={panel === "audio" ? "Audio" : panel === "subs" ? "Sous-titres" : "Informations de lecture"}
          bind:this={panelElement}
        >
          {#if panel === "audio"}
            <fieldset>
              <legend>Audio</legend>
              {#each audioTracks as t (t.id)}
                <label class="choice">
                  <input
                    type="radio"
                    name="audio"
                    checked={t.selected}
                    onchange={() => run({ type: "setAudioTrack", id: t.id })}
                  />
                  <span>{trackLabel(t)}</span>
                </label>
              {:else}
                <p class="muted">Aucune piste audio.</p>
              {/each}
            </fieldset>
          {:else if panel === "subs"}
            <fieldset>
              <legend>Sous-titres</legend>
              <label class="choice">
                <input
                  type="radio"
                  name="subs"
                  checked={subsOff}
                  onchange={() => run({ type: "setSubtitleTrack", id: null })}
                />
                <span>Désactivés</span>
              </label>
              {#each subTracks as t (t.id)}
                <label class="choice">
                  <input
                    type="radio"
                    name="subs"
                    checked={t.selected}
                    onchange={() => run({ type: "setSubtitleTrack", id: t.id })}
                  />
                  <span>{trackLabel(t)}</span>
                </label>
              {/each}
            </fieldset>
          {:else}
            <h2 tabindex="-1">Informations de lecture</h2>
            <dl>
              <dt>Serveur</dt>
              <dd>{decision}</dd>
              {#if diag}
                <dt>Décodage</dt>
                <dd>{decoding(diag)}</dd>
                <dt>Vidéo</dt>
                <dd>
                  {joined(
                    diag.videoCodec,
                    diag.videoWidth && diag.videoHeight ? `${diag.videoWidth}×${diag.videoHeight}` : null,
                  )}
                </dd>
                <dt>Audio</dt>
                <dd>{joined(diag.audioCodec, diag.audioOutput)}</dd>
                <dt>Sortie</dt>
                <dd>{joined(diag.videoOutput, diag.gpuApi)}</dd>
                <dt>Images perdues</dt>
                <dd>{(diag.frameDropCount ?? 0) + (diag.decoderFrameDropCount ?? 0)}</dd>
                <dt>Moteur</dt>
                <dd>{joined(diag.mpvVersion, diag.container)}</dd>
              {:else}
                <dt>Moteur</dt>
                <dd>Diagnostic indisponible</dd>
              {/if}
            </dl>
          {/if}
        </div>
      {/if}

      <div class="timeline">
        <span class="time">{formatTime(position)}</span>
        <input
          class="seek"
          type="range"
          min="0"
          max={duration || 0}
          step="1"
          value={position}
          style:--progress={`${progress}%`}
          oninput={onSeekInput}
          onchange={onSeekChange}
          aria-label="Position de lecture"
          aria-valuetext={`${formatTime(position)} sur ${formatTime(duration)}`}
        />
        <span class="time" title="Temps restant">{formatRemaining(position, duration)}</span>
      </div>

      <div class="buttons">
        <div class="group">
          <button
            type="button"
            class="round"
            aria-label={`Reculer de ${SKIP_SECONDS} secondes`}
            title="Reculer de 10 s (←)"
            onclick={() => run({ type: "seekRelative", seconds: -SKIP_SECONDS })}
          >
            {@render icon("rewind")}
          </button>
          <button
            type="button"
            class="round main"
            aria-label={paused ? "Lecture" : "Pause"}
            title={paused ? "Lecture (Espace)" : "Pause (Espace)"}
            onclick={() => run({ type: "togglePause" })}
          >
            {@render icon(paused ? "play" : "pause")}
          </button>
          <button
            type="button"
            class="round"
            aria-label={`Avancer de ${SKIP_SECONDS} secondes`}
            title="Avancer de 10 s (→)"
            onclick={() => run({ type: "seekRelative", seconds: SKIP_SECONDS })}
          >
            {@render icon("forward")}
          </button>
          <div class="volume">
            <button
              type="button"
              class="round"
              aria-label={volume.muted ? "Rétablir le son" : "Couper le son"}
              aria-pressed={volume.muted}
              title={volume.muted ? "Rétablir le son (M)" : "Couper le son (M)"}
              onclick={() => changeVolume(toggleMute(volume))}
            >
              {@render icon(shownVolume === 0 ? "muted" : "volume")}
            </button>
            <input
              class="level"
              type="range"
              min="0"
              max="100"
              step="1"
              value={shownVolume}
              style:--progress={`${shownVolume}%`}
              oninput={(e) => changeVolume(setLevel(Number(e.currentTarget.value)))}
              aria-label="Volume"
              aria-valuetext={`${shownVolume} %`}
            />
          </div>
        </div>
        <div class="group">
          <button
            type="button"
            class="round"
            class:on={panel === "audio"}
            aria-label="Audio"
            aria-expanded={panel === "audio"}
            title="Audio"
            bind:this={audioButton}
            onclick={() => openPanel("audio")}
          >
            {@render icon("audio")}
          </button>
          <button
            type="button"
            class="round"
            class:on={panel === "subs"}
            aria-label="Sous-titres"
            aria-expanded={panel === "subs"}
            title="Sous-titres"
            bind:this={subsButton}
            onclick={() => openPanel("subs")}
          >
            {@render icon("subs")}
          </button>
          <button
            type="button"
            class="round"
            class:on={panel === "info"}
            aria-label="Informations de lecture"
            aria-expanded={panel === "info"}
            title="Informations de lecture (I)"
            bind:this={infoButton}
            onclick={() => openPanel("info")}
          >
            {@render icon("info")}
          </button>
          <button
            type="button"
            class="round"
            aria-label={fullscreen ? "Quitter le plein écran" : "Plein écran"}
            title={fullscreen ? "Quitter le plein écran (F)" : "Plein écran (F)"}
            onclick={() => toggleFullscreen()}
          >
            {@render icon(fullscreen ? "windowed" : "fullscreen")}
          </button>
        </div>
      </div>
    </section>
    {/if}
  {/if}
</div>

<style>
  .player {
    position: fixed;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    color: var(--color-text);
  }
  .player.opening {
    background: var(--color-bg);
    justify-content: center;
    align-items: center;
  }
  .player.hidden {
    cursor: none;
  }
  .surface {
    position: absolute;
    inset: 0;
    background: transparent;
    border: 0;
    padding: 0;
    cursor: inherit;
  }
  .surface:focus-visible {
    outline: none;
  }
  /* The gradients let clicks through to the surface; their content does not. */
  .chrome {
    position: relative;
    pointer-events: none;
    transition: opacity var(--motion-base) ease;
  }
  .chrome > * {
    pointer-events: auto;
  }
  .player.hidden .chrome {
    opacity: 0;
  }
  .player.hidden .chrome * {
    pointer-events: none;
  }

  .opening-panel {
    width: min(560px, calc(100% - 32px));
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    padding: var(--space-6);
    display: grid;
    gap: var(--space-2);
    justify-items: start;
  }
  .opening-panel h1 {
    margin: 0;
    font-size: 24px;
  }

  .top {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-6) 56px var(--space-4);
    background: linear-gradient(rgb(0 0 0 / 70%), rgb(0 0 0 / 0%));
  }
  .heading {
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }
  .heading h1 {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
    line-height: 1.25;
    text-shadow: 0 1px 3px rgb(0 0 0 / 60%);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .kicker {
    margin: 0 0 2px;
    color: var(--color-accent);
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .decision {
    flex: none;
    max-width: 40%;
    margin-top: 6px;
    color: var(--color-text);
    font-size: 12px;
    text-shadow: 0 1px 3px rgb(0 0 0 / 80%);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .bottom {
    display: grid;
    gap: var(--space-2);
    padding: 56px var(--space-6) var(--space-4);
    background: linear-gradient(rgb(0 0 0 / 0%), rgb(0 0 0 / 75%));
  }
  .timeline {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }
  .time {
    min-width: 6ch;
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    color: var(--color-text);
    text-shadow: 0 1px 3px rgb(0 0 0 / 80%);
  }
  .time:last-child {
    text-align: right;
  }
  .buttons {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .group {
    display: flex;
    align-items: center;
    gap: var(--space-1);
  }

  .round {
    display: inline-grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: var(--color-text);
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .round:hover,
  .round.on {
    background: rgb(255 255 255 / 14%);
  }
  .round.main {
    width: 52px;
    height: 52px;
    background: rgb(255 255 255 / 16%);
  }
  .round.main:hover {
    background: rgb(255 255 255 / 26%);
  }
  .icon {
    width: 24px;
    height: 24px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .icon .solid {
    fill: currentColor;
    stroke: none;
  }
  .icon text {
    fill: currentColor;
    stroke: none;
    font-size: 7px;
    font-weight: 700;
    font-family: var(--font-ui);
  }
  .round.main .icon {
    width: 28px;
    height: 28px;
  }

  input[type="range"] {
    appearance: none;
    background: transparent;
    margin: 0;
    cursor: pointer;
    height: 24px;
  }
  input[type="range"]::-webkit-slider-runnable-track {
    height: 4px;
    border-radius: 2px;
    background: linear-gradient(
      to right,
      var(--color-accent) var(--progress, 0%),
      rgb(255 255 255 / 28%) var(--progress, 0%)
    );
    transition: height var(--motion-fast);
  }
  input[type="range"]::-webkit-slider-thumb {
    appearance: none;
    width: 14px;
    height: 14px;
    margin-top: -5px;
    border-radius: 50%;
    background: var(--color-text);
    box-shadow: 0 1px 4px rgb(0 0 0 / 50%);
  }
  .seek {
    flex: 1;
  }
  .seek:hover::-webkit-slider-runnable-track,
  .seek:focus-visible::-webkit-slider-runnable-track {
    height: 6px;
  }
  .seek:hover::-webkit-slider-thumb,
  .seek:focus-visible::-webkit-slider-thumb {
    margin-top: -4px;
  }
  .volume {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    margin-left: var(--space-3);
  }
  .level {
    width: 96px;
  }

  .panel {
    position: absolute;
    right: var(--space-6);
    bottom: calc(100% - 40px);
    width: min(340px, calc(100vw - 32px));
    max-height: min(60vh, 420px);
    overflow-y: auto;
    padding: var(--space-3);
    background: rgb(21 25 34 / 94%);
    border-radius: var(--radius-md);
    box-shadow: 0 8px 32px rgb(0 0 0 / 45%);
  }
  .panel.info {
    width: min(420px, calc(100vw - 32px));
  }
  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 2px;
  }
  legend,
  .panel h2 {
    padding: 0 var(--space-2) var(--space-2);
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-secondary);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .panel h2:focus {
    outline: none;
  }
  .choice {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2);
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  .choice:hover {
    background: rgb(255 255 255 / 8%);
  }
  .choice input {
    accent-color: var(--color-accent);
    margin: 0;
  }
  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: var(--space-1) var(--space-4);
    margin: 0;
    padding: 0 var(--space-2) var(--space-1);
    font-size: 13px;
  }
  dt {
    color: var(--color-text-secondary);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }

  .buffering {
    position: absolute;
    top: 50%;
    left: 50%;
    translate: -50% -50%;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-4);
    border-radius: var(--radius-sm);
    background: rgb(11 13 17 / 70%);
    color: var(--color-text-secondary);
    font-size: 13px;
    pointer-events: none;
  }
  .spinner {
    width: 16px;
    height: 16px;
    border: 2px solid rgb(255 255 255 / 25%);
    border-top-color: var(--color-accent);
    border-radius: 50%;
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to {
      rotate: 360deg;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation-duration: 3s;
    }
  }

  .muted {
    margin: 0;
    color: var(--color-text-secondary);
  }
  .error {
    margin: 0;
    color: var(--color-danger);
  }
  .floating {
    position: absolute;
    top: 50%;
    left: 50%;
    translate: -50% -50%;
    background: rgb(11 13 17 / 85%);
    padding: var(--space-2) var(--space-4);
    border-radius: var(--radius-sm);
  }
  .text-button {
    margin-top: var(--space-2);
    background: var(--color-elevated);
    color: var(--color-text);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .text-button:hover {
    filter: brightness(1.15);
  }

  /* End of an episode (ADR 0018): no video under the page any more, so the
     player paints its own background. */
  .player.ended {
    background:
      radial-gradient(ellipse at 80% 100%, rgb(85 182 232 / 14%), transparent 60%),
      var(--color-bg);
  }
  .up-next {
    position: relative;
    align-self: flex-end;
    width: min(420px, calc(100% - 2 * var(--space-4)));
    margin: 0 var(--space-6) var(--space-6);
    padding: var(--space-4) var(--space-6) var(--space-6);
    display: grid;
    gap: var(--space-1);
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    box-shadow: 0 24px 60px rgb(0 0 0 / 50%);
  }
  .up-next .label {
    margin: 0 0 var(--space-2);
    color: var(--color-text-secondary);
    font-size: 13px;
    font-weight: 600;
  }
  .up-next h2 {
    margin: 0;
    font-size: 22px;
    font-weight: 650;
    line-height: 1.25;
  }
  .countdown {
    margin: var(--space-3) 0 0;
    color: var(--color-text-secondary);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }
  .gauge {
    display: block;
    height: 3px;
    border-radius: 2px;
    background: rgb(255 255 255 / 14%);
    overflow: hidden;
  }
  .gauge span {
    display: block;
    height: 100%;
    background: var(--color-accent);
    transition: width 1s linear;
  }
  @media (prefers-reduced-motion: reduce) {
    .gauge span {
      transition: none;
    }
  }
  .actions {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }
  .actions .text-button {
    margin-top: 0;
  }
  /* The card takes the focus on its own, often after a mouse action, when
     :focus-visible would not match: Enter must show what it will do. */
  .actions button:focus {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .play-now {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-6) var(--space-2) var(--space-4);
    background: #fff;
    color: #0b0d11;
    border: 0;
    border-radius: var(--radius-sm);
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }
  .play-now:hover {
    background: #e6e8ec;
  }
  .play-now .icon {
    width: 18px;
    height: 18px;
  }

  @media (max-width: 640px) {
    .top,
    .bottom {
      padding-left: var(--space-4);
      padding-right: var(--space-4);
    }
    .level {
      display: none;
    }
    .panel {
      right: var(--space-4);
    }
  }
</style>
