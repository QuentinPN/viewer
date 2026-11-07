<script lang="ts">
// Recovery screen (ADR 0012, DESIGN.md "Connexion perdue"): the server does not
// answer. The session is kept; automatic attempts follow the coordinator's
// backoff and the user can retry now or switch server.
import { onMount } from "svelte";
import { errorHint } from "../emby/api";
import type { EmbyError, SavedServer } from "../emby/types";
import Logo from "../window/Logo.svelte";

interface Props {
  server: SavedServer;
  error: EmbyError;
  checking: boolean;
  retryAt: number | null;
  gaveUp: boolean;
  /** Shown over the signed-in app, which stays behind it. */
  overApp: boolean;
  onRetry: () => void;
  onChangeServer: () => void;
}

let {
  server,
  error,
  checking,
  retryAt,
  gaveUp,
  overApp,
  onRetry,
  onChangeServer,
}: Props = $props();

let now = $state(Date.now());
let retryButton = $state<HTMLButtonElement | null>(null);

const seconds = $derived(
  retryAt === null ? null : Math.max(0, Math.ceil((retryAt - now) / 1000)),
);
const status = $derived(
  checking
    ? "Vérification de la connexion…"
    : seconds !== null
      ? `Nouvelle tentative dans ${seconds} s`
      : gaveUp
        ? "Tentatives automatiques suspendues. Elles reprendront dès que le réseau revient, ou avec « Réessayer »."
        : "",
);

onMount(() => {
  retryButton?.focus({ preventScroll: true });
  const timer = setInterval(() => (now = Date.now()), 1000);
  return () => clearInterval(timer);
});
</script>

<div class="screen" class:over={overApp}>
  <div
    class="card"
    role="alertdialog"
    aria-modal="true"
    aria-labelledby="lost-title"
    aria-describedby="lost-desc"
  >
    <div class="logo"><Logo size={56} /></div>
    <h1 id="lost-title">Serveur injoignable</h1>
    <p id="lost-desc" class="lead">
      {server.name} ne répond pas. Votre session est conservée : rien n'est perdu.
    </p>
    <div class="server">
      <p class="server-name">{server.name}</p>
      <p class="server-meta">{server.url} · {server.userName}</p>
    </div>
    <div class="error">
      <p>{error.message}</p>
      {#if errorHint(error)}<p class="hint">{errorHint(error)}</p>{/if}
    </div>
    <p class="status" aria-live="polite" aria-busy={checking}>
      {#if checking}<span class="spinner" aria-hidden="true"></span>{/if}
      {status}
    </p>
    <div class="actions">
      <button
        type="button"
        bind:this={retryButton}
        onclick={onRetry}
        disabled={checking}
      >
        Réessayer
      </button>
      <button type="button" class="secondary" onclick={onChangeServer}>
        Changer de serveur
      </button>
    </div>
  </div>
</div>

<style>
  .screen {
    box-sizing: border-box;
    height: 100%;
    overflow-y: auto;
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background:
      radial-gradient(60% 60% at 50% 45%, rgb(42 50 66 / 55%) 0%, rgb(0 0 0 / 0%) 100%),
      var(--color-bg);
  }
  .card {
    box-sizing: border-box;
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
  .server {
    background: rgb(0 0 0 / 30%);
    border: 1px solid var(--separator);
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
  .error {
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
  button {
    height: 50px;
    padding: 0 var(--space-6);
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
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  button.secondary {
    background: var(--fill-glass);
    color: var(--color-text);
  }
  button.secondary:hover:not(:disabled) {
    background: var(--fill-glass-hover);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .screen.over {
    position: fixed;
    inset: 0;
    z-index: 20;
    height: auto;
    padding-top: calc(var(--titlebar-height) + var(--space-4));
    background: rgb(0 0 0 / 60%);
    backdrop-filter: blur(12px);
  }
  .card {
    width: min(480px, 100%);
  }
  .lead {
    margin-bottom: var(--space-4);
  }
  .error {
    margin-top: var(--space-3);
  }
  .status {
    min-height: 1.5em;
    margin: var(--space-4) 0 0;
    color: var(--color-text-secondary);
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
  }
  .spinner {
    width: 14px;
    height: 14px;
    flex: none;
    border-radius: 50%;
    border: 2px solid rgb(255 255 255 / 20%);
    border-top-color: var(--color-text);
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
      border-color: var(--color-text);
    }
  }
  .actions {
    display: grid;
    gap: var(--space-2);
    margin-top: var(--space-6);
  }
  /* Short windows: the card stays whole. */
  @media (max-height: 860px) {
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
