<script lang="ts">
// Player of a playback started from Emby (ADR 0017): title at the top, controls
// that fade after a few seconds without activity, audio, subtitles and
// diagnostic panels. The video is the native surface under this transparent page.
// At the end of an episode, a card offers the next one (ADR 0018). During an
// intro or the credits, a button skips them (ADR 0030). Chapters are marked on
// the seek bar and listed in their own panel (ADR 0034).
import { onDestroy, onMount, tick, untrack } from "svelte";
import { embyApi } from "../emby/api";
import type {
  Chapter,
  EpisodeLink,
  PlaybackStart,
  Segment,
} from "../emby/types";
import { capInEffect } from "../settings/quality";
import TitleBar from "../window/TitleBar.svelte";
import { playerApi } from "./api";
import {
  formatSpeed,
  readNightMode,
  SPEEDS,
  saveNightMode,
  speedBadge,
} from "./audiomodes";
import ChaptersPanel from "./ChaptersPanel.svelte";
import { chapterIndex, chapterMarks, chapterTarget } from "./chapters";
import {
  changeDelay,
  type DelayChange,
  type DelayKind,
  type Delays,
  formatDelay,
  NO_DELAYS,
} from "./delay";
import { endLabel, formatClock, untilNextMinute } from "./endtime";
import {
  canFallBack,
  formatRemaining,
  formatTime,
  openFailure,
  playbackHeading,
  playMethodLabel,
  trackLabel,
} from "./format";
import { mapPlayerKey } from "./keyboard";
import {
  activeSegment,
  NO_SKIP_SHOWN,
  offersNextToo,
  readSkip,
  type SkipAction,
  type SkipShown,
  segmentKey,
  skipAction,
  skipHidesIn,
  skipShown,
  trackSkipShown,
} from "./segments";
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
  /**
   * The direct source did not open (ADR 0023): asks the server for a transcode of
   * the same item from the same start; resolves with an error message, `null`
   * once the app shows it.
   */
  onFallback: (itemId: string, resume: boolean) => Promise<string | null>;
}

let { playback, onExit, onInterrupted, onPlayNext, onFallback }: Props =
  $props();

/** Same margin as Rust's early end of file (`EARLY_EOF_TICKS`). */
const EARLY_EOF_SECONDS = 30;
/** Controls fade after this long without pointer or key. */
const HIDE_AFTER_MS = 3000;
const SKIP_SECONDS = 10;

type Panel = "audio" | "subs" | "info" | "chapters" | "options";

let active = $state(false);
/**
 * A position was seen, so the file really played: a later failure is an interruption.
 * `fileLoaded` is not enough, it comes before anything is decoded.
 */
let everPlayed = false;
/** Transcode being requested after the direct source failed to open. */
let fallingBack = $state(false);
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
/** Wall clock of the clock and « Fin à » (ADR 0073): each position, and each minute. */
let now = $state(Date.now());
let clockTimer: ReturnType<typeof setTimeout> | undefined;
/** Playback speed in effect; 1 is « Normale ». */
let speed = $state(1);
/** Night mode, kept on this PC (ADR 0072). */
let nightMode = $state(readNightMode(storage()));
let optionsButton = $state<HTMLButtonElement | null>(null);
/** Delays of the file being played; each new file starts from none (ADR 0062). */
let delays = $state<Delays>({ ...NO_DELAYS });

let audioButton = $state<HTMLButtonElement | null>(null);
let subsButton = $state<HTMLButtonElement | null>(null);
let infoButton = $state<HTMLButtonElement | null>(null);
let chaptersButton = $state<HTMLButtonElement | null>(null);
let panelElement = $state<HTMLElement | null>(null);
/** Next episode of the series, looked up while this one plays. */
let nextEpisode = $state<EpisodeLink | null>(null);
/** Seconds left on the "Épisode suivant" card, shown once the episode ended. */
let upNextLeft = $state<number | null>(null);
/** Next episode being opened, until the app shows its playback. */
let launching = $state<EpisodeLink | null>(null);
let playNowButton = $state<HTMLButtonElement | null>(null);
let stopCountdown: (() => void) | undefined;
/** Intro and credits of this playback; empty until the lookup answers. */
let segments = $state<Segment[]>([]);
/** Read at each playback, so a change in Paramètres applies to the next one. */
let skipSetting = readSkip(storage());
/** Segments already skipped on their own: once per playback. */
let autoSkipped = new Set<string>();
/** When the button of the segment being played appeared (ADR 0089). */
let skipSince = $state<SkipShown>(NO_SKIP_SHOWN);
/** Clock of `skipVisible`: set when a segment starts and when its button hides. */
let skipNow = $state(0);
let skipTimer: ReturnType<typeof setTimeout> | undefined;
/** Chapters of this playback; empty until the lookup answers, or with fewer than two. */
let chapters = $state<Chapter[]>([]);

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
const segment = $derived(
  active && upNext === null && panel === null && !launching
    ? activeSegment(segments, position, duration)
    : null,
);
const skip = $derived(
  segment ? skipAction(segment, duration, nextEpisode !== null) : null,
);
const skipAlsoNext = $derived(
  segment ? offersNextToo(segment, duration, nextEpisode !== null) : false,
);
// The countdown follows the position only: a panel or the up-next card over
// the segment do not restart it.
const skipKey = $derived.by(() => {
  const playing = active ? activeSegment(segments, position, duration) : null;
  return playing ? `${playback.itemId}:${segmentKey(playing)}` : null;
});
const decision = $derived(playMethodLabel(playback));
const chapterNow = $derived(chapterIndex(chapters, position));
const chapterName = $derived(chapters[chapterNow]?.name ?? null);
const marks = $derived(chapterMarks(chapters, duration));
const audioTracks = $derived(tracks.filter((t) => t.kind === "audio"));
const subTracks = $derived(tracks.filter((t) => t.kind === "sub"));
const subsOff = $derived(!subTracks.some((t) => t.selected));
const progress = $derived(
  duration > 0 ? Math.min(100, (position / duration) * 100) : 0,
);
const shownVolume = $derived(effectiveVolume(volume));
const clock = $derived(formatClock(now));
const badge = $derived(speedBadge(speed));
const endsLabel = $derived(endLabel(now, position, duration, speed));
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
// Ten seconds on its own, then only with the controls (ADR 0089).
const skipVisible = $derived(
  skip !== null && skipShown(skipSince, skipNow, chromeVisible),
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

// Segments of each playback; an answer for an older one is dropped. Nothing is
// shown when the lookup fails.
$effect(() => {
  const { itemId } = playback;
  segments = [];
  autoSkipped = new Set();
  skipSetting = readSkip(storage());
  embyApi
    .segments(itemId, skipSetting.external)
    .then((found) => {
      if (playback.itemId === itemId) segments = found;
    })
    .catch(() => {
      // No button then.
    });
});

// Chapters of each playback; an answer for an older one is dropped. Nothing is
// shown when the lookup fails.
$effect(() => {
  const { itemId } = playback;
  chapters = [];
  embyApi
    .chapters(itemId)
    .then((found) => {
      if (playback.itemId === itemId) chapters = found;
    })
    .catch(() => {
      // No marks and no panel then.
    });
});

// With "Passer automatiquement", each segment is skipped once: seeking back into
// it shows the button again.
$effect(() => {
  if (!segment || !skip || !skipSetting.auto) return;
  const key = segmentKey(segment);
  if (autoSkipped.has(key)) return;
  autoSkipped.add(key);
  // Credits up to the end start the countdown, so it can still be cancelled.
  if (skip.kind === "next") void offerNext();
  else runSkip(skip);
});

// Each segment entered starts the button's countdown; leaving it, by its end or
// a seek before its start, clears it.
$effect(() => {
  const key = skipKey;
  const now = Date.now();
  const shown = trackSkipShown(
    untrack(() => skipSince),
    key,
    now,
  );
  skipSince = shown;
  skipNow = now;
  clearTimeout(skipTimer);
  const left = skipHidesIn(shown, now);
  if (left !== null)
    skipTimer = setTimeout(() => {
      skipNow = Date.now();
    }, left);
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
      void applyModes();
      wake();
      break;
    case "timePos":
      everPlayed = true;
      if (!scrubbing) position = ev.seconds;
      now = Date.now();
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
      } else if (ev.reason === "error") {
        if (canFallBack(everPlayed, playback.method) && !fallingBack)
          void fallBack(ev.error);
        else {
          // Never played: back to the opening screen, which explains and has "Retour".
          active = false;
          error = openFailure(ev.error);
        }
      } else if (ev.reason === "eof") {
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
    if (diag.timePos != null) everPlayed = true;
    // An engine that answers may still be opening its file (ADR 0027).
    if (diag.container != null) active = true;
    tracks = await playerApi.tracks();
    await applyVolume();
    await applyModes();
  } catch {
    // Not started yet: `fileLoaded` will come.
  }
  wake();
  tickClock();
});

/** Moves the clock on at the start of each minute, even while paused. */
function tickClock() {
  now = Date.now();
  clockTimer = setTimeout(tickClock, untilNextMinute(now));
}

onDestroy(() => {
  clearTimeout(clockTimer);
  stopCountdown?.();
  unlisten?.();
  clearInterval(diagTimer);
  clearTimeout(hideTimer);
  clearTimeout(skipTimer);
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

/** Speed and night mode are engine options: set again for each file. */
async function applyModes() {
  delays = { ...NO_DELAYS };
  await run({ type: "setSubtitleDelay", seconds: 0 });
  await run({ type: "setAudioDelay", seconds: 0 });
  await run({ type: "setSpeed", speed });
  await run({ type: "setNightMode", on: nightMode });
}

function changeSpeed(next: number) {
  speed = next;
  if (active) void run({ type: "setSpeed", speed: next });
}

function changeDelayOf(kind: DelayKind, change: DelayChange) {
  const seconds = changeDelay(delays[kind], change);
  delays = { ...delays, [kind]: seconds };
  void run(
    kind === "subtitle"
      ? { type: "setSubtitleDelay", seconds }
      : { type: "setAudioDelay", seconds },
  );
}

function changeNightMode(on: boolean) {
  nightMode = on;
  saveNightMode(storage(), on);
  if (active) void run({ type: "setNightMode", on });
}

function changeVolume(next: VolumeSetting) {
  volume = next;
  saveVolume(storage(), next);
  if (active) void applyVolume();
}

/** The direct source did not open: same item again, as a transcode (ADR 0023). */
async function fallBack(detail?: string | null) {
  fallingBack = true;
  active = false;
  everPlayed = false;
  error = null;
  position = 0;
  duration = 0;
  paused = false;
  buffering = false;
  tracks = [];
  diag = null;
  const failure = await onFallback(playback.itemId, playback.resume);
  fallingBack = false;
  if (failure) error = `${openFailure(detail)} ${failure}`;
}

/** An error once the file played, or an end of file far from the duration. */
function interruptedBy(reason: EndReason): boolean {
  if (!everPlayed) return false;
  if (reason === "error") return true;
  return (
    reason === "eof" && duration > 0 && duration - position > EARLY_EOF_SECONDS
  );
}

/** The episode ended: the card counts down, then the next one starts. */
async function offerNext() {
  // Skipping the credits may already have opened it before the end of file.
  if (upNextLeft !== null) return;
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
  everPlayed = false;
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

function runSkip(action: SkipAction) {
  if (action.kind === "next") void playNext();
  else void run({ type: "seekAbsolute", seconds: action.to });
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
      : which === "chapters"
        ? chaptersButton
        : which === "options"
          ? optionsButton
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
    panelElement?.querySelector<HTMLElement>(
      "input:checked, [aria-current='true']",
    ) ?? panelElement?.querySelector<HTMLElement>("input, button, [tabindex]");
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
  const inCheckbox =
    target instanceof HTMLInputElement && target.type === "checkbox";
  if (inCheckbox && e.key === " ") return;
  const inTextField =
    target instanceof HTMLInputElement && target.type === "text";
  // Enter skips while the button shows, whatever control has the focus. Once
  // it hid, Enter only brings it back with the controls (ADR 0089).
  if (e.key === "Enter" && skip && !inTextField) {
    e.preventDefault();
    if (skipVisible) runSkip(skip);
    else wake();
    return;
  }
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
    case "subtitleDelay":
      changeDelayOf("subtitle", action.change);
      break;
    case "chapter": {
      const to = chapterTarget(chapters, position, action.delta);
      if (to !== null) void run({ type: "seekAbsolute", seconds: to });
      break;
    }
  }
}

function goToChapter(start: number) {
  closePanel();
  void run({ type: "seekAbsolute", seconds: start });
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
    {:else if name === "chapters"}
      <path d="M9 6.5h11M9 12h11M9 17.5h11" />
      <path d="M4.5 6.5h.2M4.5 12h.2M4.5 17.5h.2" />
    {:else if name === "options"}
      <path d="M4 7h10M18 7h2M4 17h4M12 17h8" />
      <circle cx="16" cy="7" r="2" />
      <circle cx="10" cy="17" r="2" />
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
      {:else if fallingBack}
        <p class="muted" role="status">
          Lecture directe impossible, passage au transcodage…
        </p>
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

    {#if !fullscreen}
      <!-- Outside the chrome: the caption buttons fade with it but always take clicks. -->
      <div
        class="caption"
        class:faded={!chromeVisible}
        role="presentation"
        onpointerenter={() => (overChrome = true)}
        onpointerleave={() => (overChrome = false)}
      >
        <TitleBar overVideo />
      </div>
    {/if}
    <header class="top chrome" class:windowed={!fullscreen}>
      <button type="button" class="round" aria-label="Revenir" title="Revenir (Échap)" onclick={() => close()}>
        {@render icon("back")}
      </button>
      <div class="heading">
        {#if heading.kicker}<p class="kicker">{heading.kicker}</p>{/if}
        <h1>{heading.name}</h1>
      </div>
      <span class="decision" title="Décision du serveur Emby">{decision}</span>
      <span class="clock" aria-label={`Il est ${clock}`}>{clock}</span>
    </header>

    {#if buffering}
      <div class="buffering" role="status">
        <span class="spinner" aria-hidden="true"></span>
        Chargement…
      </div>
    {/if}
    {#if error}<p class="error floating" role="alert">{error}</p>{/if}

    {#if skip && skipVisible}
      <div class="skip" role="group" aria-label={skip.label}>
        {#if skipAlsoNext}
          <button type="button" class="skip-next" onclick={() => playNext()}>Épisode suivant</button>
        {/if}
        <button
          type="button"
          class="skip-main"
          title={`${skip.label} (Entrée)`}
          aria-keyshortcuts="Enter"
          onclick={() => runSkip(skip)}
        >
          {skip.label}
        </button>
      </div>
    {/if}

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
          aria-label={panel === "audio"
            ? "Audio"
            : panel === "subs"
              ? "Sous-titres"
              : panel === "chapters"
                ? "Chapitres"
                : panel === "options"
                  ? "Options de lecture"
                  : "Informations de lecture"}
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
          {:else if panel === "options"}
            <fieldset>
              <legend>Vitesse</legend>
              {#each SPEEDS as s (s)}
                <label class="choice">
                  <input
                    type="radio"
                    name="speed"
                    checked={speed === s}
                    onchange={() => changeSpeed(s)}
                  />
                  <span>{formatSpeed(s)}</span>
                </label>
              {/each}
            </fieldset>
            <fieldset>
              <legend>Synchronisation</legend>
              {#each [["subtitle", "Sous-titres", "Z / X"], ["audio", "Audio", null]] as const as [kind, name, keys] (kind)}
                <div class="stepper" role="group" aria-label={`Décalage ${kind === "subtitle" ? "des sous-titres" : "audio"}`}>
                  <span class="stepper-name">{name}</span>
                  <button
                    type="button"
                    class="step"
                    aria-label="Plus tôt"
                    title={keys ? `Plus tôt (${keys.split(" / ")[0]})` : "Plus tôt"}
                    onclick={() => changeDelayOf(kind, "earlier")}
                  >−</button>
                  <button
                    type="button"
                    class="step-value"
                    title="Remettre à zéro"
                    aria-label={`${formatDelay(delays[kind])}, remettre à zéro`}
                    onclick={() => changeDelayOf(kind, "reset")}
                  >{formatDelay(delays[kind])}</button>
                  <button
                    type="button"
                    class="step"
                    aria-label="Plus tard"
                    title={keys ? `Plus tard (${keys.split(" / ")[1]})` : "Plus tard"}
                    onclick={() => changeDelayOf(kind, "later")}
                  >+</button>
                </div>
              {/each}
            </fieldset>
            <fieldset>
              <legend>Son</legend>
              <label class="choice">
                <input
                  type="checkbox"
                  checked={nightMode}
                  onchange={(e) => changeNightMode(e.currentTarget.checked)}
                />
                <span class="stacked">
                  Mode nuit
                  <small>Réduit l'écart entre dialogues et explosions</small>
                </span>
              </label>
            </fieldset>
          {:else if panel === "chapters"}
            <ChaptersPanel {chapters} current={chapterNow} onSelect={goToChapter} />
          {:else}
            <h2 tabindex="-1">Informations de lecture</h2>
            <dl>
              <dt>Serveur</dt>
              <dd>{decision}</dd>
              <dt>Débit maximal</dt>
              <dd>{capInEffect(playback.bitrateCap, playback.localAddress)}</dd>
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
          aria-valuetext={`${formatTime(position)} sur ${formatTime(duration)}${chapterName ? `, ${chapterName}` : ""}`}
        />
        {#if marks.length > 0}
          <div class="marks" aria-hidden="true">
            {#each marks as mark (mark)}<span style:left={`${mark}%`}></span>{/each}
          </div>
        {/if}
        <div class="times">
          <span class="time">{formatTime(position)}</span>
          {#if chapterName}<span class="chapter-name">{chapterName}</span>{/if}
          <span class="end-times">
            {#if delays.subtitle !== 0}<span class="time ends" role="status" title="Décalage des sous-titres">Sous-titres {formatDelay(delays.subtitle)}</span>{/if}
            {#if badge}<span class="time ends" title="Vitesse de lecture">{badge}</span>{/if}
            {#if endsLabel}<span class="time ends">{endsLabel}</span>{/if}
            <span class="time" title="Temps restant">{formatRemaining(position, duration)}</span>
          </span>
        </div>
      </div>

      <div class="buttons">
        <div class="group start">
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
        <div class="group transport">
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
        </div>
        <div class="group end">
          {#if chapters.length > 0}
            <button
              type="button"
              class="round"
              class:on={panel === "chapters"}
              aria-label="Chapitres"
              aria-expanded={panel === "chapters"}
              aria-keyshortcuts="PageUp PageDown"
              title="Chapitres (Pg préc. / Pg suiv.)"
              bind:this={chaptersButton}
              onclick={() => openPanel("chapters")}
            >
              {@render icon("chapters")}
            </button>
          {/if}
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
            class:on={panel === "options"}
            aria-label="Options de lecture"
            aria-expanded={panel === "options"}
            title="Options de lecture"
            bind:this={optionsButton}
            onclick={() => openPanel("options")}
          >
            {@render icon("options")}
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
  /* Out of the flex flow: as an item, it pushed the top bar to the middle of the
     window when not fullscreen (its title bar is fixed, so the wrapper has no height). */
  .caption {
    position: absolute;
    transition: opacity var(--motion-base) ease;
  }
  .caption.faded {
    opacity: 0;
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
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-4) 32px 80px;
    background: linear-gradient(rgb(0 0 0 / 70%), rgb(0 0 0 / 0%));
  }
  .top > .round {
    flex: none;
    border: 1px solid var(--separator);
    background: var(--material);
    backdrop-filter: var(--material-blur);
  }
  .top > .round:hover {
    background: var(--material-thick);
  }
  .top.windowed {
    padding-top: calc(var(--space-4) + var(--titlebar-height));
  }
  .heading {
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }
  .heading h1 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
    line-height: 1.25;
    letter-spacing: -0.01em;
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
    color: var(--color-text-secondary);
    font-size: 13px;
    text-shadow: 0 1px 3px rgb(0 0 0 / 80%);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .bottom {
    --side: clamp(var(--space-4), 5vw, 80px);
    display: grid;
    gap: var(--space-2);
    padding: 120px var(--side) 32px;
    background: linear-gradient(rgb(0 0 0 / 0%), rgb(0 0 0 / 75%));
  }
  .timeline {
    position: relative;
    display: grid;
  }
  .times {
    display: flex;
    justify-content: space-between;
    gap: var(--space-4);
  }
  /* Chapter starts (ADR 0034): thin dark cuts across the 24 px high seek bar. */
  .marks {
    position: absolute;
    inset: 0 0 auto;
    height: 24px;
    pointer-events: none;
  }
  .marks span {
    position: absolute;
    top: 50%;
    width: 2px;
    height: 8px;
    translate: -1px -50%;
    background: rgb(0 0 0 / 55%);
  }
  .chapter-name {
    flex: 1;
    min-width: 0;
    text-align: center;
    font-size: 13px;
    color: var(--color-text-secondary);
    text-shadow: 0 1px 3px rgb(0 0 0 / 80%);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .time {
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    color: rgb(235 235 245 / 80%);
    text-shadow: 0 1px 3px rgb(0 0 0 / 80%);
  }
  .clock {
    flex: none;
    font-size: 20px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    text-shadow: 0 1px 3px rgb(0 0 0 / 60%);
  }
  .end-times {
    display: flex;
    gap: var(--space-3);
  }
  .ends {
    color: var(--color-text-secondary);
  }
  .buttons {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    gap: var(--space-2);
  }
  .group {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .group.transport {
    gap: 28px;
  }
  .group.end {
    justify-content: flex-end;
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
  .round:hover {
    background: rgb(255 255 255 / 14%);
  }
  .round.on {
    background: rgb(255 255 255 / 20%);
  }
  .transport .round:not(.main) {
    width: 52px;
    height: 52px;
  }
  .round.main {
    width: 72px;
    height: 72px;
    background: rgb(255 255 255 / 92%);
    color: #000;
    box-shadow: 0 12px 32px rgb(0 0 0 / 45%);
  }
  .round.main:hover {
    background: #fff;
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
    width: 30px;
    height: 30px;
  }
  .round:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
    border-radius: 3px;
    background: linear-gradient(
      to right,
      #fff var(--progress, 0%),
      rgb(255 255 255 / 25%) var(--progress, 0%)
    );
    transition: height var(--motion-fast);
  }
  input[type="range"]::-webkit-slider-thumb {
    appearance: none;
    width: 16px;
    height: 16px;
    margin-top: -6px;
    border-radius: 50%;
    background: var(--color-text);
    box-shadow: 0 1px 4px rgb(0 0 0 / 50%);
  }
  .seek {
    width: 100%;
  }
  .seek::-webkit-slider-runnable-track {
    height: 6px;
  }
  .seek::-webkit-slider-thumb {
    margin-top: -5px;
  }
  .seek:hover::-webkit-slider-runnable-track,
  .seek:focus-visible::-webkit-slider-runnable-track {
    height: 8px;
  }
  .seek:hover::-webkit-slider-thumb,
  .seek:focus-visible::-webkit-slider-thumb {
    width: 18px;
    height: 18px;
    margin-top: -5px;
  }
  .seek:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 4px;
    border-radius: 4px;
  }
  .volume {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .level {
    width: 120px;
  }

  .panel {
    position: absolute;
    right: var(--side);
    bottom: calc(100% - 96px);
    width: min(340px, calc(100vw - 32px));
    /* Stays below the title bar in short windows (150 % scaling). */
    max-height: min(60vh, 420px, calc(100vh - 240px));
    overflow-y: auto;
    padding: 18px;
    background: rgb(36 36 42 / 72%);
    backdrop-filter: blur(40px) saturate(180%);
    border: 1px solid rgb(255 255 255 / 10%);
    border-radius: 20px;
    box-shadow: 0 30px 60px rgb(0 0 0 / 50%);
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
  }
  .panel h2:focus {
    outline: none;
  }
  .choice {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-height: 40px;
    padding: 0 10px;
    border-radius: var(--radius-sm);
    font-size: 15px;
    cursor: pointer;
  }
  .choice:hover {
    background: rgb(255 255 255 / 8%);
  }
  .choice:has(input:checked) {
    background: rgb(235 235 245 / 14%);
  }
  .stepper {
    display: grid;
    grid-template-columns: 1fr 36px minmax(80px, auto) 36px;
    align-items: center;
    gap: var(--space-1);
    min-height: 40px;
    padding: 0 10px;
    font-size: 15px;
  }
  .stepper button {
    height: 32px;
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
  }
  .stepper button:hover {
    background: var(--fill-glass-hover);
  }
  .stepper button:focus-visible,
  .choice:has(input:focus-visible) {
    outline: 2px solid var(--color-accent);
    outline-offset: 1px;
  }
  .stacked {
    display: grid;
    padding: var(--space-1) 0;
  }
  .stacked small {
    font-size: 12px;
    color: var(--color-text-secondary);
  }
  fieldset + fieldset {
    margin-top: var(--space-3);
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
    border-radius: var(--radius-pill);
    background: var(--material);
    backdrop-filter: var(--material-blur);
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
    background: var(--material);
    backdrop-filter: var(--material-blur);
    padding: var(--space-2) var(--space-4);
    border-radius: var(--radius-sm);
  }
  .text-button {
    margin-top: var(--space-2);
    background: var(--fill-glass);
    color: var(--color-text);
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .text-button:hover {
    background: var(--fill-glass-hover);
  }

  /* End of an episode (ADR 0018): no video under the page any more, so the
     player paints its own background. */
  .player.ended {
    background: var(--color-bg);
  }
  .up-next {
    position: relative;
    align-self: flex-end;
    width: min(420px, calc(100% - 2 * var(--space-4)));
    margin: 0 var(--space-6) var(--space-6);
    padding: var(--space-4) var(--space-6) var(--space-6);
    display: grid;
    gap: var(--space-1);
    background: rgb(36 36 42 / 72%);
    backdrop-filter: blur(40px) saturate(180%);
    border: 1px solid rgb(255 255 255 / 10%);
    border-radius: 20px;
    box-shadow: 0 30px 60px rgb(0 0 0 / 50%);
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
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 25%);
    overflow: hidden;
  }
  .gauge span {
    display: block;
    height: 100%;
    background: #fff;
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
    background: var(--color-primary);
    color: var(--color-on-primary);
    border: 0;
    border-radius: var(--radius-sm);
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }
  .play-now:hover {
    background: #fff;
  }
  .play-now .icon {
    width: 18px;
    height: 18px;
  }

  /* Skip intro or credits (ADR 0030): above the controls, and still there for
     10 s when they fade (ADR 0089). */
  .skip {
    position: absolute;
    right: clamp(var(--space-4), 5vw, 80px);
    bottom: 190px;
    z-index: 1;
    display: flex;
    gap: var(--space-2);
  }
  .skip button {
    height: 44px;
    padding: 0 22px;
    border: 0;
    border-radius: var(--radius-sm);
    font-size: 15px;
    font: inherit;
    font-weight: 650;
    cursor: pointer;
    box-shadow: 0 4px 16px rgb(0 0 0 / 40%);
  }
  .skip-main {
    background: var(--color-primary);
    color: var(--color-on-primary);
  }
  .skip-main:hover {
    background: #fff;
  }
  .skip-next {
    background: var(--material);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
  }
  .skip-next:hover {
    background: var(--material-thick);
  }
  .skip button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }

  @media (max-width: 640px) {
    .skip {
      right: var(--space-4);
    }
    .top {
      padding-left: var(--space-4);
      padding-right: var(--space-4);
    }
    .group.transport {
      gap: var(--space-2);
    }
    .level {
      display: none;
    }
  }
</style>
