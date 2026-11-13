<script lang="ts">
// Réglages › Mises à jour (ADR 0112): the version, a manual check that shares the
// background loop's path on the Rust side, and the restart once an update is ready.
import { onMount } from "svelte";
import { updaterApi } from "../updater/api";
import { createCheckStore } from "../updater/check";

interface Props {
  version: string | null;
}

let { version }: Props = $props();

const store = createCheckStore(updaterApi);

onMount(() => {
  void store.start();
  return () => store.dispose();
});
</script>

<section class="panel" aria-labelledby="settings-updates">
  <h2 id="settings-updates">Mises à jour</h2>
  <p class="lead">
    Viewer {version ? `version ${version}` : "— version inconnue"}. Les mises à
    jour se téléchargent seules en arrière-plan ; tu peux aussi vérifier maintenant.
  </p>

  <button
    type="button"
    class="secondary"
    onclick={() => void store.check()}
    aria-disabled={$store.kind === "checking" ? "true" : undefined}
  >
    {$store.kind === "checking" ? "Recherche…" : "Rechercher des mises à jour"}
  </button>

  <div class="status" role="status">
    {#if $store.kind === "checking"}
      <p class="muted">
        Recherche en cours… Si une version plus récente existe, elle est téléchargée
        et vérifiée.
      </p>
    {:else if $store.kind === "upToDate"}
      <p class="ok">Viewer est à jour.</p>
    {:else if $store.kind === "available"}
      <p>
        La version {$store.version} est disponible.
        <small class="muted">
          Build de développement : rien n'est téléchargé ni installé. Les mises à
          jour s'appliquent depuis la version installée.
        </small>
      </p>
    {:else if $store.kind === "ready"}
      <p class="ok">Mise à jour {$store.info.version} prête.</p>
    {:else if $store.kind === "failed"}
      <p class="problem-text">{$store.message}</p>
    {/if}
  </div>

  {#if $store.kind === "ready"}
    <button
      type="button"
      class="primary"
      onclick={() => void store.install()}
      aria-disabled={$store.installing ? "true" : undefined}
    >
      {$store.installing ? "Redémarrage…" : "Redémarrer maintenant"}
    </button>
    {#if $store.error}
      <p class="problem-text" role="alert">{$store.error}</p>
    {/if}
  {/if}
</section>

<style>
  /* Same panel as the other sections of Settings.svelte. */
  .panel {
    max-width: 640px;
    margin-top: var(--space-6);
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
  p {
    margin: 0;
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  .muted {
    color: var(--color-text-muted);
  }
  small {
    display: block;
    margin-top: 2px;
    font-size: 13px;
    max-width: 58ch;
  }
  .ok {
    color: var(--color-success);
  }
  .problem-text {
    color: var(--color-danger);
    max-width: 60ch;
  }
  button {
    font: inherit;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
  }
  .primary {
    background: var(--color-accent);
    color: #04121b;
    border: 0;
    font-weight: 600;
  }
  .secondary {
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
</style>
