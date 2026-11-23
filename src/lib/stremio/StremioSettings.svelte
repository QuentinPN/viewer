<script lang="ts">
// Réglages › Stremio: sign in to the Stremio account whose addons provide
// extra streams. The password only goes through Go to the sign-in call: it is
// cleared from the field after every attempt and never kept here.
import { onMount, tick } from "svelte";
import "../requests/requests.css";
import { stremioApi, toStremioError } from "./api";
import { addonLabel, statusLabel } from "./status";
import type { StremioError, StremioState, StremioStatus } from "./types";

const TONES: Record<StremioState, string> = {
  signed_out: "muted",
  signed_in: "success",
  reauth_required: "warning",
  unreachable: "danger",
};

let status = $state<StremioStatus | null>(null);
let error = $state<StremioError | null>(null);
let busy = $state(false);
let email = $state("");
let password = $state("");
let panel = $state<HTMLElement | null>(null);
let heading = $state<HTMLHeadingElement | null>(null);

const addons = $derived(status?.addons ?? []);
const showForm = $derived(
  status?.state === "signed_out" || status?.state === "reauth_required",
);
const canSubmit = $derived(!busy && email.trim() !== "" && password !== "");

/** Runs one action at a time; its error shows under the section. */
async function run(action: () => Promise<StremioStatus>) {
  if (busy) return;
  const hadFocus = panel?.contains(document.activeElement) ?? false;
  busy = true;
  error = null;
  try {
    status = await action();
  } catch (e) {
    error = toStremioError(e);
  } finally {
    busy = false;
  }
  // The control that had the focus may be gone with the old state.
  await tick();
  if (hadFocus && !panel?.contains(document.activeElement)) heading?.focus();
}

function signIn(e: SubmitEvent) {
  e.preventDefault();
  if (!canSubmit) return;
  const secret = password;
  password = "";
  void run(() => stremioApi.signIn(email.trim(), secret));
}

onMount(() => void run(stremioApi.status));
</script>

<section
  class="panel"
  aria-labelledby="settings-stremio"
  aria-busy={busy}
  bind:this={panel}
>
  <h2 id="settings-stremio" tabindex="-1" bind:this={heading}>Stremio</h2>

  {#if status}
    <p class="state">
      <span class="dot ur-tone-{TONES[status.state]}" aria-hidden="true"></span>
      <span>{statusLabel(status)}</span>
    </p>
  {:else if !error}
    <div class="skeleton" aria-hidden="true">
      <span></span>
      <span></span>
    </div>
    <p class="ur-sr-only" role="status">Lecture de l'état…</p>
  {/if}

  {#if status?.state === "signed_in"}
    {#if addons.length}
      <ul class="addons">
        {#each addons as a, i (`${i}:${a.name}`)}
          <li>{addonLabel(a)}</li>
        {/each}
      </ul>
    {:else}
      <p class="ur-muted small">Aucun addon de flux sur ce compte.</p>
    {/if}
  {/if}

  {#if showForm}
    <form class="login" onsubmit={signIn}>
      <label class="glass-field" for="stremio-email">
        <span>Adresse e-mail</span>
      </label>
      <input
        id="stremio-email"
        class="ur-input"
        type="email"
        autocomplete="username"
        spellcheck="false"
        bind:value={email}
      />
      <label class="glass-field" for="stremio-password">
        <span>Mot de passe</span>
      </label>
      <input
        id="stremio-password"
        class="ur-input"
        type="password"
        autocomplete="current-password"
        bind:value={password}
      />
      <button type="submit" class="ur-btn" disabled={!canSubmit}>
        Se connecter
      </button>
    </form>
  {/if}

  {#if status?.state === "signed_in" || status?.state === "reauth_required"}
    <button
      type="button"
      class="ur-btn"
      disabled={busy}
      onclick={() => void run(stremioApi.signOut)}
    >
      Se déconnecter
    </button>
  {/if}

  {#if status?.state === "unreachable" || (!status && error)}
    <button
      type="button"
      class="ur-btn"
      disabled={busy}
      onclick={() => void run(stremioApi.status)}
    >
      Réessayer
    </button>
  {/if}

  {#if error}<p class="ur-error small" role="alert">{error.message}</p>{/if}
</section>

<style>
  .panel {
    background: var(--color-surface);
    border-radius: var(--radius-md);
    padding: var(--space-6);
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  h2:focus {
    outline: none;
  }
  p {
    margin: 0;
  }
  .small {
    font-size: 13px;
  }
  .state {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: currentColor;
  }
  /* Static bars, no shimmer: nothing to stop under reduced motion. */
  .skeleton {
    display: grid;
    gap: var(--space-2);
    width: 100%;
    max-width: 320px;
  }
  .skeleton span {
    height: 14px;
    border-radius: var(--radius-sm);
    background: var(--fill-glass);
  }
  .skeleton span + span {
    width: 60%;
  }
  .addons {
    list-style: none;
    margin: 0;
    padding: 0;
    width: 100%;
    max-width: 520px;
  }
  .addons li {
    padding: var(--space-2) 0;
    border-bottom: 1px solid var(--separator);
  }
  .login {
    display: grid;
    gap: var(--space-2);
    width: 100%;
    max-width: 520px;
    justify-items: start;
  }
  .login input {
    width: 100%;
  }
  .login button {
    margin-top: var(--space-2);
  }
</style>
