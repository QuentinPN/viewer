<script lang="ts">
// Gate 1 prototype: minimal player overlay used to validate native video
// composition, focus and keyboard handling. Emby playback uses PlayerView
// (ADR 0017); this view stays as the test bench of the prototype mode.
import { onDestroy, onMount } from "svelte";
import type { PlaybackStart } from "../emby/types";
import { playerApi } from "./api";
import { formatTime, playMethodLabel, trackLabel } from "./format";
import { mapPlayerKey } from "./keyboard";
import type {
  AppInfo,
  EndReason,
  PlayerCommand,
  PlayerDiagnostics,
  PlayerEvent,
  Track,
} from "./types";

interface Props {
  /** Playback started by `emby_play`; the open form is not shown then. */
  playback?: PlaybackStart | null;
  onExit?: () => void;
  /**
   * Emby playback that ended on an error or well before the end (the stream
   * broke, ADR 0012): the player closes and the app offers to resume.
   */
  onInterrupted?: (itemId: string) => void;
}

let { playback = null, onExit, onInterrupted }: Props = $props();

/** Same margin as Rust's early end of file (`EARLY_EOF_TICKS`). */
const EARLY_EOF_SECONDS = 30;

let info = $state<AppInfo | null>(null);
let source = $state("");
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

const audioTracks = $derived(tracks.filter((t) => t.kind === "audio"));
const subTracks = $derived(tracks.filter((t) => t.kind === "sub"));
const selectedAudio = $derived(audioTracks.find((t) => t.selected)?.id ?? null);
const selectedSub = $derived(subTracks.find((t) => t.selected)?.id ?? null);

let unlisten: (() => void) | undefined;
let diagTimer: ReturnType<typeof setInterval> | undefined;

function onEvent(ev: PlayerEvent) {
  switch (ev.type) {
    case "fileLoaded":
      active = true;
      error = null;
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
      if (playback && onInterrupted && interruptedBy(ev.reason)) {
        void close(() => onInterrupted(playback.itemId));
      } else if (ev.reason === "error")
        error = `Lecture impossible : ${ev.error ?? "erreur inconnue"}`;
      else if (ev.reason === "eof" && onExit) void close();
      break;
    case "shutdown":
      active = false;
      break;
  }
}

onMount(async () => {
  unlisten = await playerApi.onEvent(onEvent);
  try {
    info = await playerApi.appInfo();
  } catch (e) {
    error = String(e);
  }
  diagTimer = setInterval(async () => {
    if (!active) return;
    try {
      diag = await playerApi.diagnostics();
      // Engine state is authoritative: resync when events were missed (e.g. engine
      // driven by the Gate 1 scenario before this view subscribed).
      if (diag.paused != null) paused = diag.paused;
    } catch {
      diag = null;
    }
  }, 1000);
  // The Gate 1 scenario starts playback from Rust; detect an already running engine.
  try {
    diag = await playerApi.diagnostics();
    active = true;
    tracks = await playerApi.tracks();
  } catch {
    active = false;
  }
});

onDestroy(() => {
  unlisten?.();
  clearInterval(diagTimer);
});

async function run(command: PlayerCommand) {
  try {
    await playerApi.command(command);
  } catch (e) {
    error = String(e);
  }
}

async function open(event: SubmitEvent) {
  event.preventDefault();
  error = null;
  try {
    await playerApi.open({ source });
    active = true;
  } catch (e) {
    error = String(e);
  }
}

/** An error once the file played, or an end of file far from the duration. */
function interruptedBy(reason: EndReason): boolean {
  if (!active) return false;
  if (reason === "error") return true;
  return (
    reason === "eof" && duration > 0 && duration - position > EARLY_EOF_SECONDS
  );
}

async function close(then: (() => void) | undefined = onExit) {
  if (fullscreen) await toggleFullscreen(false).catch(() => {});
  await playerApi.close();
  active = false;
  tracks = [];
  diag = null;
  position = 0;
  duration = 0;
  then?.();
}

async function toggleFullscreen(force?: boolean) {
  fullscreen = await playerApi.setFullscreen(force ?? !fullscreen);
}

function onKeydown(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null;
  const inTextField =
    target?.tagName === "INPUT" && (target as HTMLInputElement).type === "text";
  const action = mapPlayerKey(e.key, { inTextField, shift: e.shiftKey });
  if (!action || (!active && !(onExit && action.kind === "exitFullscreen")))
    return;
  e.preventDefault();
  if (action.kind === "command") void run(action.command);
  else if (action.kind === "toggleFullscreen") void toggleFullscreen();
  else if (action.kind !== "exitFullscreen") return;
  else if (fullscreen) void toggleFullscreen(false);
  // Escape outside fullscreen goes back, like on the detail page.
  else if (onExit) void close();
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

function trackId(value: string): number | null {
  return value === "" ? null : Number(value);
}
</script>

<svelte:window onkeydown={onKeydown} />

<div class="root" class:idle={!active}>
  {#if !active && playback}
    <main class="open-panel" aria-busy={!error}>
      <h1>Lecture</h1>
      {#if error}
        <p class="error" role="alert">{error}</p>
      {:else}
        <p class="subtitle">Ouverture du flux…</p>
      {/if}
      <button type="button" onclick={() => close()}>Retour</button>
    </main>
  {:else if !active}
    <main class="open-panel">
      <h1>Viewer</h1>
      <p class="subtitle">Prototype de lecture native — Gate 1</p>
      <form onsubmit={open}>
        <label for="source">Fichier local (chemin absolu) ou URL http(s)</label>
        <input
          id="source"
          type="text"
          bind:value={source}
          placeholder="D:\Vidéos\exemple.mkv"
          autocomplete="off"
          spellcheck="false"
        />
        <button type="submit" disabled={source.trim() === ""}>Lire</button>
      </form>
      {#if info && !info.libmpvFound}
        <p class="error" role="alert">libmpv-2.dll introuvable. Lance scripts/fetch-libmpv.ps1.</p>
      {/if}
      {#if error}<p class="error" role="alert">{error}</p>{/if}
    </main>
  {:else}
    <header class="top">
      {#if playback}
        <span class="badge" title="Décision du serveur Emby">{playMethodLabel(playback)}</span>
      {:else}
        <span class="badge">Prototype Gate 1 · surface {info?.surfaceMode ?? "?"}</span>
      {/if}
      {#if diag}
        <span class="badge" title="Diagnostic du moteur">
          {diag.videoCodec ?? "?"} · {diag.videoWidth}×{diag.videoHeight} · décodage
          {diag.hwdecCurrent && diag.hwdecCurrent !== "no" ? `matériel (${diag.hwdecCurrent})` : "logiciel"}
          · {diag.videoOutput ?? "?"}
        </span>
      {/if}
      {#if buffering}<span class="badge">Mise en mémoire tampon…</span>{/if}
    </header>

    {#if error}<p class="error floating" role="alert">{error}</p>{/if}

    <section class="controls" aria-label="Commandes de lecture">
      <button onclick={() => run({ type: "togglePause" })} aria-label={paused ? "Lecture" : "Pause"}>
        {paused ? "▶" : "❚❚"}
      </button>
      <span class="time">{formatTime(position)}</span>
      <input
        class="seek"
        type="range"
        min="0"
        max={duration || 0}
        step="1"
        value={position}
        oninput={onSeekInput}
        onchange={onSeekChange}
        aria-label="Position de lecture"
      />
      <span class="time">{formatTime(duration)}</span>

      <label class="select">
        <span>Audio</span>
        <select
          value={selectedAudio ?? ""}
          onchange={(e) => run({ type: "setAudioTrack", id: trackId(e.currentTarget.value) })}
        >
          {#each audioTracks as t (t.id)}<option value={t.id}>{trackLabel(t)}</option>{/each}
        </select>
      </label>
      <label class="select">
        <span>Sous-titres</span>
        <select
          value={selectedSub ?? ""}
          onchange={(e) => run({ type: "setSubtitleTrack", id: trackId(e.currentTarget.value) })}
        >
          <option value="">Désactivés</option>
          {#each subTracks as t (t.id)}<option value={t.id}>{trackLabel(t)}</option>{/each}
        </select>
      </label>
      <button onclick={() => toggleFullscreen()} aria-label={fullscreen ? "Quitter le plein écran" : "Plein écran"}>
        {fullscreen ? "⤡" : "⤢"}
      </button>
      <button onclick={() => close()} aria-label={onExit ? "Arrêter et revenir à la fiche" : "Fermer la lecture"}>✕</button>
    </section>
  {/if}
</div>

<style>
  .root {
    height: 100%;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
  }
  .root.idle {
    background: var(--color-bg);
    justify-content: center;
    align-items: center;
  }
  .open-panel {
    width: min(560px, calc(100% - 32px));
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    padding: var(--space-6);
  }
  h1 {
    margin: 0;
    font-size: 28px;
  }
  .subtitle {
    color: var(--color-text-secondary);
    margin: var(--space-1) 0 var(--space-6);
  }
  form {
    display: grid;
    gap: var(--space-2);
  }
  label {
    color: var(--color-text-secondary);
    font-size: 13px;
  }
  input[type="text"],
  select {
    background: var(--color-elevated);
    color: var(--color-text);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    font: inherit;
  }
  button {
    background: var(--color-accent);
    color: #04121b;
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    transition: filter var(--motion-fast);
  }
  button:hover {
    filter: brightness(1.1);
  }
  button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .error {
    color: var(--color-danger);
  }
  .floating {
    align-self: center;
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    padding: var(--space-2) var(--space-4);
    border-radius: var(--radius-sm);
  }
  .top {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    padding: var(--space-4);
  }
  .badge {
    background: var(--material);
    backdrop-filter: var(--material-blur);
    color: var(--color-text-secondary);
    border-radius: var(--radius-sm);
    padding: var(--space-1) var(--space-3);
    font-size: 13px;
  }
  .controls {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-3);
    margin: var(--space-4);
    padding: var(--space-3) var(--space-4);
    background: rgb(21 25 34 / 82%);
    border-radius: var(--radius-md);
  }
  .controls button {
    min-width: 40px;
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .time {
    font-variant-numeric: tabular-nums;
    color: var(--color-text-secondary);
    min-width: 4.5ch;
  }
  .seek {
    flex: 1 1 200px;
    accent-color: var(--color-accent);
  }
  .select {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .select select {
    max-width: 220px;
  }
</style>
