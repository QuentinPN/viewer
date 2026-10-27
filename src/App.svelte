<script lang="ts">
import { onMount } from "svelte";
import { embyApi, toEmbyError } from "./lib/emby/api";
import SignIn from "./lib/emby/SignIn.svelte";
import type { EmbyError, SignedInUser } from "./lib/emby/types";
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
  <main class="screen">
    <section class="card" aria-labelledby="account-title">
      <h1 id="account-title">Bonjour {user.userName}</h1>
      <p class="muted">
        Connecté à <strong>{user.server.name}</strong> · Emby {user.server.version}
        {#if !user.server.encrypted}· connexion non chiffrée (réseau local){/if}
      </p>
      <p class="muted">La bibliothèque arrive avec la suite de la Gate 2.</p>
      <div class="actions">
        <button type="button" onclick={() => (view = { kind: "player", user })}>
          Prototype de lecture
        </button>
        <button type="button" class="secondary" onclick={signOut} disabled={signingOut}>
          {signingOut ? "Déconnexion…" : "Se déconnecter"}
        </button>
      </div>
    </section>
  </main>
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
  .card {
    width: min(520px, 100%);
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    padding: var(--space-6);
  }
  h1 {
    margin: 0 0 var(--space-2);
    font-size: 26px;
  }
  .muted {
    color: var(--color-text-secondary);
    margin: var(--space-1) 0;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-3);
    margin-top: var(--space-6);
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
  }
  button.secondary {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
</style>
