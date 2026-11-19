<script lang="ts">
// The « Demander » tab (ADR 0125): its state first, then Découvrir, Films and
// Séries once Request is ready; the setup in place otherwise.
import { onMount } from "svelte";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import RequestsSetup from "./RequestsSetup.svelte";
import type { RequestsError, UrStatus } from "./types";

let status = $state<UrStatus | null>(null);
let error = $state<RequestsError | null>(null);

async function load() {
  error = null;
  try {
    status = await requestsApi.status();
  } catch (e) {
    error = toRequestsError(e);
  }
}

onMount(load);
</script>

<header class="heading">
  <h1>Demander</h1>
</header>

{#if error}
  <div class="state" role="alert">
    <p>{error.message}</p>
    <button type="button" class="ur-btn" onclick={load}>Réessayer</button>
  </div>
{:else if status === null}
  <p class="ur-muted" aria-busy="true">Connexion à Request…</p>
{:else if status.state !== "ready"}
  <RequestsSetup {status} onStatus={(s) => (status = s)} />
{/if}

<style>
  .heading {
    margin-bottom: var(--space-4);
  }
  h1 {
    margin: 0;
    font-size: 34px;
  }
  .state {
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  p {
    margin: 0;
  }
</style>
