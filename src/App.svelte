<script lang="ts">
import { onMount } from "svelte";
import { embyApi, toEmbyError } from "./lib/emby/api";
import SignIn from "./lib/emby/SignIn.svelte";
import type { EmbyError, SignedInUser } from "./lib/emby/types";
import LibraryBrowser from "./lib/library/LibraryBrowser.svelte";
import { playerApi } from "./lib/player/api";
import PlayerSpike from "./lib/player/PlayerSpike.svelte";

// Session first: restore the saved Emby session, else show onboarding. The Gate 1
// player prototype stays reachable until the Gate 3 player replaces it.
type View =
  | { kind: "starting" }
  | { kind: "signedOut"; lastUrl: string | null; notice: EmbyError | null }
  | { kind: "signedIn"; user: SignedInUser }
  | { kind: "player"; user: SignedInUser | null };

let view = $state<View>({ kind: "starting" });
let signingOut = $state(false);

onMount(async () => {
  // The Gate 1 scenario drives the player from Rust: go straight to it.
  if ((await playerApi.appInfo().catch(() => null))?.spike) {
    view = { kind: "player", user: null };
    return;
  }
  try {
    const user = await embyApi.restore();
    view = user
      ? { kind: "signedIn", user }
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
    onSignedIn={(user) => (view = { kind: "signedIn", user })}
  />
{:else if view.kind === "signedIn"}
  {@const user = view.user}
  <LibraryBrowser
    {user}
    {signingOut}
    onSignOut={signOut}
    onOpenPlayer={() => (view = { kind: "player", user })}
    onSessionExpired={sessionExpired}
  />
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
  .muted {
    color: var(--color-text-secondary);
  }
</style>
