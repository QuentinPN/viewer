<script lang="ts">
// Onboarding: server address, connection test, then sign-in (DESIGN.md). With
// `reauth`, the server answered but refused the saved token (ADR 0012): server
// and user are prefilled and only the password is asked.
import { onMount, tick, untrack } from "svelte";
import { embyApi, errorHint, toEmbyError } from "./api";
import type {
  EmbyError,
  SavedServer,
  ServerSummary,
  SignedInUser,
} from "./types";

interface Props {
  /** Shown above the form, e.g. why the password is asked again. */
  notice?: EmbyError | null;
  /** The session to sign in again; the server step is skipped. */
  reauth?: SavedServer | null;
  onSignedIn: (user: SignedInUser) => void;
  /** "Changer de serveur" in reauthentication mode leaves this account. */
  onChangeServer?: () => void;
  /** "Revenir à …": a set-aside session, back without a password. */
  onResumeSaved?: (serverId: string, userId: string) => void;
}

let {
  notice = null,
  reauth = null,
  onSignedIn,
  onChangeServer,
  onResumeSaved,
}: Props = $props();

// Prefill only: later prop changes must not overwrite what the user typed.
const initial = untrack(() => reauth);
let url = $state(initial?.url ?? "");
let server = $state<ServerSummary | null>(
  initial
    ? {
        url: initial.url,
        name: initial.name,
        version: initial.version,
        id: initial.id,
        encrypted: initial.encrypted,
      }
    : null,
);
let username = $state(initial?.userName ?? "");
let password = $state("");
let busy = $state(false);
let error = $state<EmbyError | null>(null);
let saved = $state<SavedServer[]>([]);
let usernameInput = $state<HTMLInputElement | null>(null);
let passwordInput = $state<HTMLInputElement | null>(null);

const shownError = $derived(error ?? notice);

onMount(() => {
  if (initial) {
    passwordInput?.focus();
    return;
  }
  void embyApi
    .savedSessions()
    .then((list) => (saved = list))
    .catch(() => {});
  void embyApi
    .lastServer()
    .then((last) => {
      if (last && url === "") url = last;
    })
    .catch(() => {});
});

async function testServer(event: SubmitEvent) {
  event.preventDefault();
  busy = true;
  error = null;
  notice = null;
  try {
    server = await embyApi.probe(url);
    url = server.url;
  } catch (e) {
    error = toEmbyError(e);
  }
  busy = false;
  // Focus only once inputs are enabled again: a disabled input ignores focus().
  await tick();
  if (server) usernameInput?.focus();
}

async function signIn(event: SubmitEvent) {
  event.preventDefault();
  if (!server) return;
  busy = true;
  error = null;
  try {
    const user = await embyApi.signIn(server.url, username, password);
    password = "";
    onSignedIn(user);
    return;
  } catch (e) {
    error = toEmbyError(e);
    password = "";
  }
  busy = false;
  await tick();
  if (error?.kind === "invalidCredentials") passwordInput?.focus();
}

function changeServer() {
  if (initial && onChangeServer) {
    onChangeServer();
    return;
  }
  server = null;
  error = null;
  password = "";
}
</script>

<main class="screen">
  <section class="card" aria-labelledby="signin-title">
    <h1 id="signin-title">Viewer</h1>
    <p class="lead">
      {#if initial}
        Votre session sur {initial.name} doit être confirmée : saisissez votre mot de passe.
      {:else}
        {server ? "Connectez-vous à votre compte Emby." : "Indiquez l'adresse de votre serveur Emby."}
      {/if}
    </p>

    {#if shownError}
      <div class="error" role="alert">
        <p>{shownError.message}</p>
        {#if errorHint(shownError)}<p class="hint">{errorHint(shownError)}</p>{/if}
      </div>
    {/if}

    {#if !server}
      <form onsubmit={testServer}>
        <label for="server-url">Adresse du serveur</label>
        <input
          id="server-url"
          type="text"
          inputmode="url"
          bind:value={url}
          placeholder="http://192.0.2.10:8096"
          autocomplete="url"
          spellcheck="false"
          disabled={busy}
        />
        <button type="submit" disabled={busy || url.trim() === ""}>
          {busy ? "Connexion au serveur…" : "Continuer"}
        </button>
      </form>
      {#if saved.length > 0 && onResumeSaved}
        <div class="saved">
          <p class="saved-title">Sessions conservées</p>
          <ul>
            {#each saved as entry (`${entry.id}/${entry.userId}`)}
              <li>
                <button
                  type="button"
                  class="saved-entry"
                  disabled={busy}
                  onclick={() => onResumeSaved(entry.id, entry.userId)}
                >
                  <span>Revenir à {entry.name}</span>
                  <span class="server-meta">{entry.userName} · {entry.url}</span>
                </button>
              </li>
            {/each}
          </ul>
        </div>
      {/if}
    {:else}
      <div class="server">
        <div>
          <p class="server-name">{server.name}</p>
          <p class="server-meta">{server.url} · Emby {server.version}</p>
        </div>
        <button type="button" class="link" onclick={changeServer} disabled={busy}>
          {initial ? "Changer de serveur" : "Changer"}
        </button>
      </div>
      {#if !server.encrypted}
        <p class="warning">
          Connexion non chiffrée (HTTP) : acceptable sur votre réseau local uniquement.
        </p>
      {/if}
      <form onsubmit={signIn}>
        <label for="username">Nom d'utilisateur</label>
        <input
          id="username"
          type="text"
          bind:this={usernameInput}
          bind:value={username}
          autocomplete="username"
          spellcheck="false"
          disabled={busy}
        />
        <label for="password">Mot de passe</label>
        <input
          id="password"
          type="password"
          bind:this={passwordInput}
          bind:value={password}
          autocomplete="current-password"
          disabled={busy}
        />
        <button type="submit" disabled={busy || username.trim() === ""}>
          {busy ? "Connexion…" : "Se connecter"}
        </button>
      </form>
    {/if}
  </section>
</main>

<style>
  .screen {
    height: 100%;
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background:
      radial-gradient(ellipse at 30% 10%, rgb(85 182 232 / 12%), transparent 60%),
      var(--color-bg);
  }
  .card {
    width: min(440px, 100%);
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    padding: var(--space-6);
    box-shadow: 0 24px 60px rgb(0 0 0 / 45%);
  }
  h1 {
    margin: 0;
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  .lead {
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
    margin-top: var(--space-2);
  }
  input {
    background: var(--color-elevated);
    color: var(--color-text);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    font: inherit;
  }
  input:focus-visible {
    border-color: var(--color-accent);
    outline: none;
  }
  button {
    margin-top: var(--space-4);
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
  button:hover:not(:disabled) {
    filter: brightness(1.1);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  button.link {
    margin: 0;
    background: transparent;
    color: var(--color-accent);
    padding: var(--space-1) var(--space-2);
  }
  .server {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--space-3);
    background: var(--color-elevated);
    border-radius: var(--radius-md);
    padding: var(--space-3) var(--space-4);
  }
  .server p {
    margin: 0;
  }
  .server-name {
    font-weight: 600;
  }
  .server-meta {
    color: var(--color-text-muted);
    font-size: 13px;
    overflow-wrap: anywhere;
  }
  .warning {
    color: var(--color-text-secondary);
    font-size: 13px;
    margin: var(--space-3) 0 0;
  }
  .saved {
    margin-top: var(--space-6);
  }
  .saved-title {
    margin: 0 0 var(--space-2);
    color: var(--color-text-secondary);
    font-size: 13px;
  }
  .saved ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-2);
  }
  button.saved-entry {
    width: 100%;
    margin: 0;
    display: grid;
    justify-items: start;
    gap: 2px;
    text-align: left;
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .error {
    border-left: 3px solid var(--color-danger);
    background: rgb(240 125 130 / 10%);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    margin-bottom: var(--space-4);
  }
  .error p {
    margin: 0;
    color: var(--color-danger);
  }
  /* Rust messages start lowercase so they can be embedded in sentences. */
  .error p::first-letter {
    text-transform: uppercase;
  }
  .error .hint {
    margin-top: var(--space-1);
    color: var(--color-text-secondary);
    font-size: 13px;
  }
</style>
