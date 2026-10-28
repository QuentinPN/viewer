<script lang="ts">
import { onMount, tick } from "svelte";
import { embyApi, toEmbyError } from "./lib/emby/api";
import SignIn from "./lib/emby/SignIn.svelte";
import type { EmbyError, PlaybackStart, SignedInUser } from "./lib/emby/types";
import LibraryBrowser from "./lib/library/LibraryBrowser.svelte";
import { playerApi } from "./lib/player/api";
import PlayerSpike from "./lib/player/PlayerSpike.svelte";

// Session first: restore the saved Emby session, else show onboarding. The Gate 1
// player prototype stays reachable until the Gate 3 player replaces it. Playback
// from Emby shows the player over the library, which stays mounted (hidden) so the
// detail page, scroll position and focus are there again after playback.
type View =
  | { kind: "starting" }
  | { kind: "signedOut"; lastUrl: string | null; notice: EmbyError | null }
  | { kind: "signedIn"; user: SignedInUser; playing: PlaybackStart | null }
  | { kind: "player"; user: SignedInUser | null };

let view = $state<View>({ kind: "starting" });
let signingOut = $state(false);
/** Element focused when playback started, focused again when it ends. */
let returnFocus: HTMLElement | null = null;

function startPlaying(start: PlaybackStart) {
  if (view.kind !== "signedIn") return;
  returnFocus =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  view = { ...view, playing: start };
}

async function stopPlaying() {
  if (view.kind !== "signedIn") return;
  view = { ...view, playing: null };
  await tick();
  returnFocus?.focus({ preventScroll: true });
  returnFocus = null;
}

onMount(async () => {
  // The Gate 1 scenario drives the player from Rust: go straight to it.
  if ((await playerApi.appInfo().catch(() => null))?.spike) {
    view = { kind: "player", user: null };
    return;
  }
  try {
    const user = await embyApi.restore();
    view = user
      ? { kind: "signedIn", user, playing: null }
      : {
          kind: "signedOut",
          lastUrl: await embyApi.lastServer(),
          notice: null,
        };
  } catch (e) {
    view = {
      kind: "signedOut",
      lastUrl: await embyApi.lastServer().catch(() => null),
      notice: toEmbyError(e),
    };
  }
});

async function sessionExpired(notice: EmbyError) {
  view = {
    kind: "signedOut",
    lastUrl: await embyApi.lastServer().catch(() => null),
    notice,
  };
}

async function signOut() {
  signingOut = true;
  try {
    await embyApi.signOut();
  } finally {
    signingOut = false;
    view = {
      kind: "signedOut",
      lastUrl: await embyApi.lastServer().catch(() => null),
      notice: null,
    };
  }
}
</script>

{#if view.kind === "starting"}
  <main class="screen" aria-busy="true">
    <p class="muted">Connexion au serveur…</p>
  </main>
{:else if view.kind === "signedOut"}
  <SignIn
    initialUrl={view.lastUrl}
    notice={view.notice}
    onSignedIn={(user) => (view = { kind: "signedIn", user, playing: null })}
  />
{:else if view.kind === "signedIn"}
  {@const user = view.user}
  <div class="layer" hidden={view.playing !== null}>
    <LibraryBrowser
      {user}
      {signingOut}
      onSignOut={signOut}
      onOpenPlayer={() => (view = { kind: "player", user })}
      onSessionExpired={sessionExpired}
      onPlay={startPlaying}
    />
  </div>
  {#if view.playing}
    <PlayerSpike playback={view.playing} onExit={stopPlaying} />
  {/if}
{:else}
  <PlayerSpike />
{/if}

<style>
  .screen {
    height: 100%;
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background: var(--color-bg);
  }
  .layer {
    height: 100%;
  }
  .layer[hidden] {
    display: none;
  }
  .muted {
    color: var(--color-text-secondary);
  }
</style>
