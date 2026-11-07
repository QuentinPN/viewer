<script lang="ts">
import { diagnosticsApi, openLogDirError } from "./diagnostics";

let opening = $state(false);
let error = $state<string | null>(null);

async function openLogs() {
  if (opening) return;
  opening = true;
  error = null;
  try {
    await diagnosticsApi.openLogDir();
  } catch (e) {
    error = openLogDirError(e);
  } finally {
    opening = false;
  }
}
</script>

<section class="panel" aria-labelledby="settings-diagnostics">
  <h2 id="settings-diagnostics">Diagnostics</h2>
  <p class="lead">
    Viewer tient un journal sur ce poste pour comprendre un problème de lecture
    ou un arrêt inattendu. Les mots de passe et jetons en sont retirés, et il n'est
    jamais envoyé : vous seul décidez de le partager.
  </p>
  <button
    type="button"
    class="secondary"
    onclick={openLogs}
    aria-disabled={opening ? "true" : undefined}
  >
    Ouvrir le dossier des journaux
  </button>
  {#if error}
    <p class="problem-text" role="alert">{error}</p>
  {/if}
</section>

<style>
  /* Same panel as the other sections of Settings.svelte. */
  .panel {
    max-width: 640px;
    background: var(--color-surface);
    border-radius: var(--radius-md);
    padding: var(--space-6);
    display: grid;
    gap: var(--space-3);
    justify-items: start;
    /* Both sides: the sibling sections' `.panel + .panel` rule does not see this one. */
    margin: var(--space-6) 0;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  p {
    margin: 0;
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  button {
    font: inherit;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
    background: transparent;
    color: var(--color-text);
    border: 1px solid var(--color-elevated);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button[aria-disabled="true"] {
    opacity: 0.55;
    cursor: default;
  }
  .problem-text {
    color: var(--color-danger);
  }
</style>
