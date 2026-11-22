<script lang="ts">
// Sign-in card (DESIGN.md). Without `reauth`: « Connexion manuelle »,
// one form with the address, the user name and the password; the address is
// probed, then the account signs in. With `reauth`: a known server and user,
// only the password is asked (« Session expirée » after a rejection).
// The password is never stored: only the session token, in the vault.
import { onMount, tick, untrack } from "svelte";
import { hostOf, isPlainHttp } from "../connection/shelf";
import Logo from "../window/Logo.svelte";
import { embyApi, errorHint, toEmbyError } from "./api";
import type { EmbyError, SavedServer, SignedInUser } from "./types";

interface Props {
  /** Shown above the form, e.g. why the password is asked again. */
  notice?: EmbyError | null;
  /** The account to sign in again: only the password is asked. */
  reauth?: SavedServer | null;
  onSignedIn: (user: SignedInUser) => void;
  /** Back button (and Escape); hidden when absent. */
  onBack?: () => void;
  backLabel?: string;
}

let {
  notice = null,
  reauth = null,
  onSignedIn,
  onBack,
  backLabel = "Retour",
}: Props = $props();

/** Errors about the address: the focus goes back to it. */
const ADDRESS_ERRORS = new Set<EmbyError["kind"]>([
  "invalidUrl",
  "insecureRemoteUrl",
  "unreachable",
  "timeout",
  "invalidResponse",
  "serverMismatch",
]);

// Prefill only: later prop changes must not overwrite what the user typed.
const initial = untrack(() => reauth);
const expired = untrack(() => notice?.kind === "sessionExpired");
let url = $state(initial?.url ?? "");
let username = $state(initial?.userName ?? "");
let password = $state("");
let busy = $state(false);
let error = $state<EmbyError | null>(null);
let urlInput = $state<HTMLInputElement | null>(null);
let usernameInput = $state<HTMLInputElement | null>(null);
let passwordInput = $state<HTMLInputElement | null>(null);

const shownError = $derived(error ?? notice);
const plainHttp = $derived(initial ? !initial.encrypted : isPlainHttp(url));
const title = $derived(
  initial
    ? expired
      ? "Session expirée"
      : `Connexion à ${initial.name}`
    : "Connexion manuelle",
);

onMount(() => {
  if (initial) {
    passwordInput?.focus();
    return;
  }
  urlInput?.focus();
  void embyApi
    .lastServer()
    .then((last) => {
      if (last && url === "") url = last;
    })
    .catch(() => {});
});

async function submit(event: SubmitEvent) {
  event.preventDefault();
  busy = true;
  error = null;
  notice = null;
  try {
    const address = initial ? initial.url : (await embyApi.probe(url)).url;
    if (!initial) url = address;
    const user = await embyApi.signIn(address, username, password);
    password = "";
    onSignedIn(user);
    return;
  } catch (e) {
    error = toEmbyError(e);
    password = "";
  }
  busy = false;
  // Focus only once inputs are enabled again: a disabled input ignores focus().
  await tick();
  if (error && ADDRESS_ERRORS.has(error.kind) && urlInput) urlInput.focus();
  else if (error?.kind === "invalidCredentials") passwordInput?.focus();
  else if (username.trim() === "") usernameInput?.focus();
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape" || !onBack || busy) return;
  event.preventDefault();
  event.stopPropagation();
  onBack();
}
</script>

<main class="screen">
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <section class="card" aria-labelledby="signin-title" onkeydown={onKeydown}>
    <div class="logo"><Logo /></div>
    <h1 id="signin-title">{title}</h1>
    <p class="lead">
      {#if initial}
        {expired
          ? `Le serveur « ${initial.name} » demande à nouveau ton mot de passe.`
          : `Saisis ton mot de passe pour le serveur « ${initial.name} ».`}
      {:else}
        Saisis l'adresse de ton serveur Emby et tes identifiants.
      {/if}
    </p>

    {#if shownError}
      <div class="error" role="alert">
        <p>{shownError.message}</p>
        {#if errorHint(shownError)}<p class="hint">{errorHint(shownError)}</p>{/if}
      </div>
    {/if}

    <form onsubmit={submit}>
      {#if initial}
        <span class="label" id="server-label">Serveur</span>
        <p class="readonly" aria-labelledby="server-label">
          {hostOf(initial.url)}<span class="version"> · Emby {initial.version}</span>
        </p>
      {:else}
        <label for="server-url">Adresse du serveur</label>
        <input
          id="server-url"
          type="text"
          inputmode="url"
          bind:this={urlInput}
          bind:value={url}
          placeholder="http://192.0.2.14:8096"
          autocomplete="url"
          spellcheck="false"
          aria-describedby="server-url-hint"
          disabled={busy}
        />
        <p class="hint" id="server-url-hint">
          Une adresse locale ou distante, par exemple https://emby.exemple.fr
        </p>
      {/if}
      {#if plainHttp}
        <p class="warning">
          Connexion non chiffrée (HTTP) : acceptable sur ton réseau local uniquement.
        </p>
      {/if}
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
      {#if !initial}
        <p class="hint">
          Le mot de passe n'est pas enregistré. Seul le jeton de session est conservé, dans le
          Gestionnaire d'identification Windows.
        </p>
      {/if}
      <div class="actions" class:single={!onBack}>
        {#if onBack}
          <button type="button" class="secondary" onclick={onBack} disabled={busy}>
            {backLabel}
          </button>
        {/if}
        <button
          type="submit"
          disabled={busy || username.trim() === "" || (!initial && url.trim() === "")}
        >
          {busy ? "Connexion…" : "Se connecter"}
        </button>
      </div>
    </form>
  </section>
</main>

<style>
  .screen {
    box-sizing: border-box;
    height: 100%;
    overflow-y: auto;
    display: grid;
    place-items: safe center;
    padding: var(--space-4);
    background:
      radial-gradient(60% 60% at 50% 45%, rgb(42 50 66 / 55%) 0%, rgb(0 0 0 / 0%) 100%),
      var(--color-bg);
  }
  .card {
    box-sizing: border-box;
    width: min(440px, 100%);
    padding: 40px;
    border-radius: 28px;
    background: rgb(36 36 42 / 68%);
    backdrop-filter: blur(40px) saturate(180%);
    border: 1px solid rgb(255 255 255 / 10%);
    box-shadow: 0 40px 80px rgb(0 0 0 / 60%);
  }
  .logo {
    display: flex;
    justify-content: center;
    margin-bottom: var(--space-4);
  }
  h1 {
    margin: 0;
    font-size: 28px;
    font-weight: 700;
    letter-spacing: -0.02em;
    text-align: center;
  }
  .lead {
    margin: 6px 0 var(--space-6);
    font-size: 15px;
    line-height: 1.5;
    color: var(--color-text-secondary);
    text-align: center;
  }
  .error {
    margin-bottom: var(--space-4);
    border-radius: var(--radius-sm);
    padding: var(--space-3) var(--space-4);
    background: rgb(255 105 97 / 14%);
    border: 1px solid rgb(255 105 97 / 30%);
  }
  .error p {
    margin: 0;
    color: var(--color-danger);
  }
  /* Rust messages start lowercase so they can be embedded in sentences. */
  .error p:first-child::first-letter {
    text-transform: uppercase;
  }
  .error .hint {
    margin-top: var(--space-1);
    color: var(--color-text-secondary);
    font-size: 13px;
  }
  form {
    display: grid;
    gap: 6px;
  }
  label,
  .label {
    margin-top: var(--space-3);
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-secondary);
  }
  form > :first-child {
    margin-top: 0;
  }
  input,
  .readonly {
    height: 44px;
    box-sizing: border-box;
    padding: 0 14px;
    border: 1px solid rgb(255 255 255 / 12%);
    border-radius: 12px;
    background: rgb(0 0 0 / 35%);
    color: var(--color-text);
    font: inherit;
    font-size: 15px;
  }
  .readonly {
    margin: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  .version {
    color: var(--color-text-muted);
  }
  input:focus-visible {
    border-color: var(--color-accent);
    outline: 1px solid var(--color-accent);
    outline-offset: 0;
  }
  .hint,
  .warning {
    margin: 0;
    color: var(--color-text-muted);
    font-size: 12.5px;
    line-height: 1.4;
  }
  .warning {
    margin-top: var(--space-1);
    color: var(--color-text-secondary);
  }
  form > .hint:not(#server-url-hint) {
    margin-top: var(--space-2);
  }
  .actions {
    margin-top: var(--space-6);
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
  }
  .actions.single {
    grid-template-columns: 1fr;
  }
  button {
    height: 50px;
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-md);
    background: var(--color-primary);
    color: var(--color-on-primary);
    font: inherit;
    font-size: 17px;
    font-weight: 600;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  button:hover:not(:disabled) {
    background: #fff;
  }
  button.secondary {
    background: var(--fill-glass);
    color: var(--color-text);
  }
  button.secondary:hover:not(:disabled) {
    background: var(--fill-glass-hover);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  /* Short windows: the card stays whole. */
  @media (max-height: 860px) {
    .actions {
      margin-top: var(--space-4);
    }
    label,
    .label {
      margin-top: var(--space-2);
    }
    .card {
      padding: 28px 32px;
    }
    .logo {
      margin-bottom: var(--space-2);
    }
    .logo :global(svg) {
      width: 48px;
      height: 48px;
    }
    .lead {
      margin-bottom: var(--space-4);
    }
  }
</style>
