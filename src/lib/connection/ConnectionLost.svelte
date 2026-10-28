<script lang="ts">
// Recovery screen (ADR 0012, DESIGN.md "Connexion perdue"): the NAS does not
// answer. The session is kept; automatic attempts follow the coordinator's
// backoff and the user can retry now or switch server.
import { onMount } from "svelte";
import { errorHint } from "../emby/api";
import type { EmbyError, SavedServer } from "../emby/types";

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
    <p class="brand">Viewer</p>
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
    height: 100%;
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background:
      radial-gradient(ellipse at 30% 10%, rgb(85 182 232 / 12%), transparent 60%),
      var(--color-bg);
  }
  .screen.over {
    position: fixed;
    inset: 0;
    z-index: 20;
    height: auto;
    background: rgb(5 8 12 / 78%);
    backdrop-filter: blur(6px);
  }
  .card {
    width: min(480px, 100%);
    background: var(--color-surface);
    border-radius: var(--radius-lg);
    padding: var(--space-6);
    box-shadow: 0 24px 60px rgb(0 0 0 / 45%);
  }
  .brand {
    margin: 0 0 var(--space-4);
    color: var(--color-text-muted);
    font-size: 13px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  h1 {
    margin: 0;
    font-size: 24px;
  }
  .lead {
    color: var(--color-text-secondary);
    margin: var(--space-1) 0 var(--space-4);
  }
  .server {
    background: var(--color-elevated);
    border-radius: var(--radius-md);
    padding: var(--space-3) var(--space-4);
  }
  .server p,
  .error p {
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
    margin-top: var(--space-3);
    border-left: 3px solid var(--color-danger);
    background: rgb(240 125 130 / 10%);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
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
  .status {
    min-height: 1.5em;
    margin: var(--space-4) 0 0;
    color: var(--color-text-secondary);
    font-size: 14px;
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .spinner {
    width: 14px;
    height: 14px;
    flex: none;
    border-radius: 50%;
    border: 2px solid var(--color-elevated);
    border-top-color: var(--color-accent);
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
      border-color: var(--color-accent);
    }
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-4);
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
  button.secondary {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  button:hover:not(:disabled) {
    filter: brightness(1.1);
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
</style>
