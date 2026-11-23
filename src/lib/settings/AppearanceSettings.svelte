<script lang="ts">
// Réglages › Apparence: the seasonal logo (locked in the debug build, which keeps the hard hat). Global (not per server or user):
// it is the app's own icon, kept on this PC like the player's volume.
import { logo } from "../brand/logo.svelte";
import { LOGO_URLS } from "../brand/logos";
</script>

<section class="panel" aria-labelledby="settings-appearance">
  <h2 id="settings-appearance">Apparence</h2>
  <label class="check">
    <input
      type="checkbox"
      checked={logo.seasonal}
      disabled={logo.debug}
      onchange={(e) => logo.setSeasonal(e.currentTarget.checked)}
    />
    <span>
      Icônes saisonnières
      <small class="muted">
        {#if logo.debug}
          Build de débogage : le raton garde son casque de chantier, quelle que soit la date.
        {:else}
          Le raton change de tenue pour Noël, Halloween… L'icône épinglée dans la barre des
          tâches reste la même.
        {/if}
      </small>
    </span>
    <img src={LOGO_URLS[logo.id]} width="40" height="40" alt="" aria-hidden="true" draggable="false" />
  </label>
</section>

<style>
  /* Same panel as the other sections of Settings.svelte. */
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
  .check {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    cursor: pointer;
  }
  .check input {
    margin: 3px 0 0;
    width: 18px;
    height: 18px;
    accent-color: var(--color-accent);
    flex: none;
  }
  .check input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .check small {
    display: block;
    margin-top: 2px;
    font-size: 13px;
    max-width: 58ch;
  }
  .check img {
    flex: none;
    margin-left: auto;
  }
  .muted {
    color: var(--color-text-muted);
  }
</style>
