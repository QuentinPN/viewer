<script lang="ts">
// Réglages › Demandes (ADR 0125): the Request address, the connection state,
// the devices signed in to this account and the sign-out.
import { onMount } from "svelte";
import "./requests.css";
import AddressForm from "./AddressForm.svelte";
import { requestsApi, toRequestsError } from "./api";
import { sessionName, stateLine } from "./labels";
import type { AuthSession, RequestsError, UrStatus } from "./types";

let status = $state<UrStatus | null>(null);
let sessions = $state<AuthSession[] | null>(null);
let sessionsError = $state<RequestsError | null>(null);
let error = $state<RequestsError | null>(null);
let busy = $state(false);
let saved = $state("");

const line = $derived(status ? stateLine(status) : null);

/** Runs one action at a time; its error shows under the section. */
async function run(action: () => Promise<UrStatus | null>) {
  if (busy) return;
  busy = true;
  error = null;
  saved = "";
  try {
    const next = await action();
    status = next ?? (await requestsApi.status());
    void loadSessions();
  } catch (e) {
    error = toRequestsError(e);
  } finally {
    busy = false;
  }
}

async function loadSessions() {
  sessionsError = null;
  if (status?.state !== "ready") {
    sessions = null;
    return;
  }
  try {
    sessions = await requestsApi.sessions();
  } catch (e) {
    sessionsError = toRequestsError(e);
  }
}

function save(address: string) {
  void run(async () => {
    const next = await requestsApi.setAddress(address);
    saved = "Adresse enregistrée.";
    return next;
  });
}

async function revoke(id: string) {
  sessionsError = null;
  try {
    await requestsApi.revokeSession(id);
    await loadSessions();
  } catch (e) {
    sessionsError = toRequestsError(e);
  }
}

onMount(() => void run(async () => null));
</script>

<section class="panel" aria-labelledby="settings-requests" aria-busy={busy}>
  <h2 id="settings-requests">Demandes</h2>
  <p class="lead">
    Demander des films et des séries à Request depuis l'onglet « Demander ».
  </p>

  <AddressForm
    id="settings-ur-address"
    address={status?.address ?? null}
    submitLabel="Enregistrer"
    {busy}
    onSubmit={save}
  />
  {#if saved}<p class="ur-muted small" role="status">{saved}</p>{/if}

  {#if line && status}
    <p class="state">
      <span class="dot ur-tone-{line.tone}" aria-hidden="true"></span>
      <span>{line.text}</span>
    </p>
    {#if status.state !== "ready" && status.message}
      <p class="ur-muted small">{status.message}</p>
    {/if}
  {:else if !error}
    <p class="ur-muted" aria-busy="true">Lecture de l'état…</p>
  {/if}

  {#if status?.state === "ready"}
    <h3>Appareils connectés</h3>
    {#if sessionsError}
      <p class="ur-error small" role="alert">{sessionsError.message}</p>
    {/if}
    {#if sessions === null && !sessionsError}
      <p class="ur-muted small" aria-busy="true">Chargement…</p>
    {:else if sessions}
      <ul class="devices">
        {#each sessions as s (s.id)}
          <li>
            <span>{sessionName(s)}</span>
            {#if s.current}
              <span class="ur-muted small">cet appareil</span>
            {:else}
              <button
                type="button"
                class="ur-link"
                aria-label="Déconnecter {sessionName(s)}"
                onclick={() => void revoke(s.id)}
              >
                Déconnecter
              </button>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
    <button
      type="button"
      class="ur-btn"
      disabled={busy}
      onclick={() =>
        void run(async () => {
          await requestsApi.signOut();
          return null;
        })}
    >
      Se déconnecter
    </button>
  {:else if status && status.state !== "notConfigured"}
    <button
      type="button"
      class="ur-btn"
      disabled={busy}
      onclick={() => void run(requestsApi.signIn)}
    >
      Se connecter
    </button>
  {/if}

  {#if error}<p class="ur-error small" role="alert">{error.message}</p>{/if}

  <p class="ur-muted small note">
    La connexion utilise votre session Emby : aucun mot de passe n'est demandé ni
    conservé.
  </p>
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
  h3 {
    margin: var(--space-2) 0 0;
    font-size: 15px;
  }
  p {
    margin: 0;
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  .small {
    font-size: 13px;
  }
  .note {
    max-width: 60ch;
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
  .devices {
    list-style: none;
    margin: 0;
    padding: 0;
    width: 100%;
    max-width: 520px;
  }
  .devices li {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-bottom: 1px solid var(--separator);
  }
</style>
