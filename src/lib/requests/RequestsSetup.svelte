<script lang="ts">
// Every state of the « Demander » tab but `ready`: first setup on the
// spot, signed out, server too old, unreachable or refused.
import "./requests.css";
import AddressForm from "./AddressForm.svelte";
import { requestsApi, toRequestsError } from "./api";
import type { RequestsError, UrStatus } from "./types";

interface Props {
  status: UrStatus;
  onStatus: (status: UrStatus) => void;
}

let { status, onStatus }: Props = $props();

let busy = $state(false);
let error = $state<RequestsError | null>(null);
let editing = $state(false);

async function run(action: () => Promise<UrStatus>) {
  if (busy) return;
  busy = true;
  error = null;
  try {
    const next = await action();
    editing = false;
    onStatus(next);
  } catch (e) {
    error = toRequestsError(e);
  } finally {
    busy = false;
  }
}

/** A new address, then the sign-in if this account had signed out. */
function connect(address: string) {
  void run(async () => {
    const next = await requestsApi.setAddress(address);
    return next.state === "signedOut" ? requestsApi.signIn() : next;
  });
}

const retry = () => void run(requestsApi.status);
</script>

<div class="setup" aria-busy={busy}>
  <div class="card">
    {#if status.state === "notConfigured" || editing}
      <h2>Demander des films et des séries</h2>
      <p class="text">
        Indique l'adresse de ton serveur Request pour demander les titres qui
        manquent à la bibliothèque. La connexion utilise ta session Emby.
      </p>
      <AddressForm
        id="setup-ur-address"
        address={status.address}
        submitLabel="Se connecter"
        {busy}
        onSubmit={connect}
      />
      <p class="ur-muted small">Modifiable ensuite dans Réglages › Demandes.</p>
      {#if editing}
        <button type="button" class="ur-link" onclick={() => (editing = false)}>
          Annuler
        </button>
      {/if}
    {:else if status.state === "signedOut"}
      <h2>Déconnecté d'Request</h2>
      <p class="text">Connecte-toi pour demander des films et des séries.</p>
      <div class="actions">
        <button
          type="button"
          class="ur-btn primary"
          disabled={busy}
          onclick={() => void run(requestsApi.signIn)}
        >
          Se connecter
        </button>
      </div>
    {:else if status.state === "tooOld"}
      <h2>Serveur Request trop ancien</h2>
      <p class="text">
        {status.message ??
          "Ce serveur ne propose pas tout ce dont Viewer a besoin."}
      </p>
      {#if status.missing.length > 0}
        <p class="ur-muted small">Fonctions manquantes :</p>
        <ul class="missing">
          {#each status.missing as feature (feature)}
            <li><code>{feature}</code></li>
          {/each}
        </ul>
      {/if}
      <div class="actions">
        <button type="button" class="ur-btn primary" disabled={busy} onclick={retry}>
          Réessayer
        </button>
        <button type="button" class="ur-btn" onclick={() => (editing = true)}>
          Changer d'adresse
        </button>
      </div>
    {:else if status.state === "unreachable"}
      <h2>Request est injoignable</h2>
      <p class="text">{status.message ?? "Le serveur ne répond pas."}</p>
      {#if status.address}<p class="ur-muted small">{status.address}</p>{/if}
      <div class="actions">
        <button type="button" class="ur-btn primary" disabled={busy} onclick={retry}>
          Réessayer
        </button>
        <button type="button" class="ur-btn" onclick={() => (editing = true)}>
          Changer d'adresse
        </button>
      </div>
    {:else}
      <h2>Connexion refusée</h2>
      <p class="text">
        {status.message ?? "Request a refusé la connexion de ce compte."}
      </p>
      <div class="actions">
        <button type="button" class="ur-btn primary" disabled={busy} onclick={retry}>
          Réessayer
        </button>
      </div>
    {/if}
    {#if error}<p class="ur-error small" role="alert">{error.message}</p>{/if}
  </div>
</div>

<style>
  .setup {
    display: grid;
    place-items: center;
    padding: 48px 0;
  }
  .card {
    width: min(560px, 100%);
    display: grid;
    gap: var(--space-3);
    justify-items: start;
    padding: 32px;
    border-radius: var(--radius-lg);
    background: var(--material-thick);
    border: 1px solid var(--separator);
  }
  h2 {
    margin: 0;
    font-size: 24px;
  }
  p {
    margin: 0;
  }
  .text {
    color: var(--color-text-secondary);
  }
  .small {
    font-size: 13px;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }
  .missing {
    margin: 0;
    padding-left: 20px;
    color: var(--color-text-secondary);
  }
</style>
