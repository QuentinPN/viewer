<script lang="ts">
import { onMount, tick } from "svelte";
import ConnectionLost from "./lib/connection/ConnectionLost.svelte";
import { ConnectionCoordinator } from "./lib/connection/coordinator";
import { appOf, type ConnectionState } from "./lib/connection/machine";
import { embyApi, errorHint, toEmbyError } from "./lib/emby/api";
import SignIn from "./lib/emby/SignIn.svelte";
import type { EmbyError, PlaybackStart } from "./lib/emby/types";
import LibraryBrowser from "./lib/library/LibraryBrowser.svelte";
import { playerApi } from "./lib/player/api";
import PlayerSpike from "./lib/player/PlayerSpike.svelte";
import PlayerView from "./lib/player/PlayerView.svelte";
import TitleBar from "./lib/window/TitleBar.svelte";

// Session first (ADR 0012): the saved Emby session is restored behind a branded
// screen, never the sign-in form. The signed-in app stays mounted (inert) under
// the recovery and reauthentication screens, so view, selection and scroll
// survive a lost connection. The Gate 1 player prototype stays reachable in
// development builds only (ADR 0025). Playback from Emby shows the player over the
// library, which stays mounted (hidden) for the same reason.

let conn = $state<ConnectionState>({ kind: "restoring" });
let spike = $state<"none" | "scenario" | "prototype">("none");
let playing = $state<PlaybackStart | null>(null);
/** Playback that ended on an error or too early: offered again (ADR 0012). */
let interrupted = $state<{ itemId: string } | null>(null);
let resuming = $state(false);
let resumeError = $state<EmbyError | null>(null);
let signingOut = $state(false);
/** Element focused when playback started, focused again when it ends. */
let returnFocus: HTMLElement | null = null;
let resumeButton = $state<HTMLButtonElement | null>(null);
/** Last element focused in the app: focused again when an overlay leaves. */
let appFocus: HTMLElement | null = null;
let wasActive = false;

const coordinator = new ConnectionCoordinator({
  restore: embyApi.restore,
  resumeSaved: embyApi.resumeSaved,
  changeServer: embyApi.changeServer,
  onChange: (state) => {
    conn = state;
    if (!appOf(state)) {
      playing = null;
      interrupted = null;
    }
  },
});

const app = $derived(appOf(conn));
const appActive = $derived(conn.kind === "authenticated");

// The recovery and reauthentication screens take the focus; on return it goes
// back where it was in the app (e.g. "Reprendre" of an interrupted playback).
$effect(() => {
  const active = appActive;
  if (active && !wasActive && appFocus?.isConnected) {
    const target = appFocus;
    void tick().then(() => target.focus({ preventScroll: true }));
  }
  wasActive = active;
});

function startPlaying(start: PlaybackStart) {
  if (!appActive) return;
  returnFocus =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  interrupted = null;
  resumeError = null;
  playing = start;
}

async function stopPlaying() {
  playing = null;
  await tick();
  returnFocus?.focus({ preventScroll: true });
  returnFocus = null;
}

/** The stream broke: back to the app, which checks the server, then offers to resume. */
async function playbackInterrupted(itemId: string) {
  playing = null;
  interrupted = { itemId };
  await tick();
  resumeButton?.focus({ preventScroll: true });
  void coordinator.verify();
}

/**
 * Next episode from the end-of-episode card (ADR 0018): the player stays open and
 * the focus still goes back, at the end, where the first playback started.
 */
async function playNext(itemId: string): Promise<string | null> {
  if (!appActive) return "La session n'est pas disponible.";
  try {
    playing = await embyApi.play(itemId, true);
    return null;
  } catch (e) {
    const error = toEmbyError(e);
    void coordinator.report(error);
    return error.message;
  }
}

/**
 * The player could not open the direct source (ADR 0023): the same item again, with
 * direct play refused so the server transcodes. Like `playNext`, the player stays open.
 */
async function playTranscoded(
  itemId: string,
  resume: boolean,
): Promise<string | null> {
  if (!appActive) return "La session n'est pas disponible.";
  try {
    playing = await embyApi.play(itemId, resume, true);
    return null;
  } catch (e) {
    const error = toEmbyError(e);
    void coordinator.report(error);
    return error.message;
  }
}

async function resumeInterrupted() {
  if (!interrupted || resuming) return;
  resuming = true;
  resumeError = null;
  try {
    startPlaying(await embyApi.play(interrupted.itemId, true));
  } catch (e) {
    resumeError = toEmbyError(e);
    void coordinator.report(resumeError);
  } finally {
    resuming = false;
  }
}

onMount(() => {
  void (async () => {
    // The Gate 1 scenario drives the player from Rust: go straight to it.
    if ((await playerApi.appInfo().catch(() => null))?.spike) {
      spike = "scenario";
      return;
    }
    await coordinator.start();
  })();
  return () => coordinator.dispose();
});

async function signOut() {
  signingOut = true;
  try {
    await embyApi.signOut();
  } finally {
    signingOut = false;
    coordinator.signedOut();
  }
}
</script>

<svelte:window ononline={() => void coordinator.online()} />

{#if playing === null}<TitleBar />{/if}

{#if spike !== "none"}
  {#if spike === "prototype"}
    <PlayerSpike onExit={() => (spike = "none")} />
  {:else}
    <PlayerSpike />
  {/if}
{:else}
  {#if app}
    {#key `${app.user.server.id}/${app.user.userId}`}
      <div
        class="layer"
        hidden={playing !== null}
        inert={!appActive}
        aria-hidden={appActive ? undefined : "true"}
        onfocusin={(e) => {
          if (e.target instanceof HTMLElement) appFocus = e.target;
        }}
      >
        <LibraryBrowser
          user={app.user}
          refresh={app.refresh}
          {signingOut}
          onSignOut={signOut}
          onOpenPlayer={() => (spike = "prototype")}
          onChangeServer={() => void coordinator.changeServer()}
          onError={(error) => void coordinator.report(error)}
          onPlay={startPlaying}
        />
        {#if interrupted}
          <div class="banner" role="status">
            <p>
              <strong>Lecture interrompue.</strong>
              {resumeError
                ? resumeError.message
                : "La position a été conservée sur le serveur."}
              {#if resumeError && errorHint(resumeError)}
                <span class="hint">{errorHint(resumeError)}</span>
              {/if}
            </p>
            <button
              type="button"
              bind:this={resumeButton}
              disabled={resuming || !appActive}
              onclick={resumeInterrupted}
            >
              {resuming ? "Ouverture…" : "Reprendre"}
            </button>
            <button
              type="button"
              class="secondary"
              onclick={() => (interrupted = null)}
            >
              Fermer
            </button>
          </div>
        {/if}
      </div>
    {/key}
    {#if playing}
      <PlayerView
        playback={playing}
        onExit={stopPlaying}
        onInterrupted={playbackInterrupted}
        onPlayNext={playNext}
        onFallback={playTranscoded}
      />
    {/if}
  {/if}

  {#if playing}
    <!-- The player keeps the screen; an interruption brings these back. -->
  {:else if conn.kind === "restoring"}
    <main class="screen" aria-busy="true">
      <div class="splash">
        <p class="brand">Viewer</p>
        <p class="muted" role="status">Ouverture de votre session…</p>
        <span class="bar" aria-hidden="true"></span>
      </div>
    </main>
  {:else if conn.kind === "server_unreachable"}
    <ConnectionLost
      server={conn.server}
      error={conn.error}
      checking={conn.checking}
      retryAt={conn.retryAt}
      gaveUp={conn.gaveUp}
      overApp={app !== null}
      onRetry={() => void coordinator.retryNow()}
      onChangeServer={() => void coordinator.changeServer()}
    />
  {:else if conn.kind === "authentication_required"}
    {#key `${conn.server.id}/${conn.server.userId}`}
      <div class:overlay={app !== null} class="full">
        <SignIn
          reauth={conn.server}
          notice={conn.error}
          onSignedIn={(user) => coordinator.signedIn(user)}
          onChangeServer={() => void coordinator.changeServer()}
        />
      </div>
    {/key}
  {:else if conn.kind === "server_configuration" || conn.kind === "signed_out"}
    {#key conn.kind}
      <SignIn
        notice={conn.kind === "signed_out" ? conn.notice : null}
        onSignedIn={(user) => coordinator.signedIn(user)}
        onResumeSaved={(serverId, userId) =>
          void coordinator.resumeSaved(serverId, userId)}
      />
    {/key}
  {/if}
{/if}

<style>
  .screen,
  .full {
    height: 100%;
  }
  .screen {
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background:
      radial-gradient(ellipse at 30% 10%, rgb(85 182 232 / 12%), transparent 60%),
      var(--color-bg);
  }
  .overlay {
    position: fixed;
    inset: 0;
    z-index: 20;
    height: auto;
  }
  .overlay :global(.screen) {
    background: rgb(5 8 12 / 78%);
    backdrop-filter: blur(6px);
  }
  .splash {
    display: grid;
    justify-items: center;
    gap: var(--space-2);
  }
  .brand {
    margin: 0;
    font-size: 32px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }
  .bar {
    width: 120px;
    height: 3px;
    border-radius: 2px;
    background: linear-gradient(
      90deg,
      transparent,
      var(--color-accent),
      transparent
    );
    background-size: 200% 100%;
    animation: sweep 1.4s ease-in-out infinite;
  }
  @keyframes sweep {
    from {
      background-position: 100% 0;
    }
    to {
      background-position: -100% 0;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .bar {
      animation: none;
      background: var(--color-accent);
      opacity: 0.6;
    }
  }
  .layer {
    height: 100%;
    position: relative;
  }
  .layer[hidden] {
    display: none;
  }
  .muted {
    margin: 0;
    color: var(--color-text-secondary);
  }
  .banner {
    position: fixed;
    left: 50%;
    bottom: var(--space-6);
    transform: translateX(-50%);
    z-index: 10;
    width: min(640px, calc(100% - 2 * var(--space-4)));
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--color-elevated);
    border-radius: var(--radius-md);
    box-shadow: 0 16px 40px rgb(0 0 0 / 45%);
  }
  .banner p {
    margin: 0;
    flex: 1;
  }
  .banner .hint {
    display: block;
    color: var(--color-text-secondary);
    font-size: 13px;
  }
  .banner button {
    background: var(--color-accent);
    color: #04121b;
    border: 0;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .banner button.secondary {
    background: transparent;
    color: var(--color-text);
  }
  .banner button:disabled {
    opacity: 0.55;
    cursor: default;
  }
</style>
