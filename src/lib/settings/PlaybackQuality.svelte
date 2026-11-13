<script lang="ts">
import { onMount } from "svelte";
import { embyApi, toEmbyError } from "../emby/api";
import type { EmbyError, PlaybackQuality } from "../emby/types";
import { addressNote, capLabel, capValue, parseCap } from "./quality";

// Streaming bitrate caps of the current server (ADR 0037), kept in Rust.
let quality = $state<PlaybackQuality | null>(null);
let loadError = $state<EmbyError | null>(null);
let saveError = $state<EmbyError | null>(null);
let saving = $state(false);

async function load() {
  loadError = null;
  try {
    quality = await embyApi.playbackQuality();
  } catch (e) {
    loadError = toEmbyError(e);
  }
}

async function change(which: "local" | "remote", value: string) {
  const current = quality;
  if (!current) return;
  const next = { ...current, [which]: parseCap(value, current.choices) };
  quality = next;
  saving = true;
  saveError = null;
  try {
    quality = await embyApi.setPlaybackQuality(next.local, next.remote);
  } catch (e) {
    // The select goes back to what is stored.
    quality = { ...current };
    saveError = toEmbyError(e);
  } finally {
    saving = false;
  }
}

onMount(load);
</script>

<section class="panel" aria-labelledby="settings-quality">
  <h2 id="settings-quality">Qualité de lecture</h2>
  <p class="lead">
    Au-delà du débit choisi, le serveur Emby convertit le film à la volée pour le
    réduire. Utile hors de chez toi, quand la connexion ne suit pas. Réglé pour ce
    serveur seulement.
  </p>

  {#if loadError}
    <div class="problem" role="alert">
      <p>Impossible de lire ce réglage : {loadError.message}</p>
      <button type="button" class="secondary" onclick={load}>Réessayer</button>
    </div>
  {:else if quality === null}
    <p class="muted" aria-busy="true">Lecture du réglage…</p>
  {:else}
    {@const q = quality}
    <div class="fields" aria-busy={saving ? "true" : undefined}>
      <label class="field">
        <span>Réseau local</span>
        <select
          value={capValue(q.local)}
          onchange={(e) => change("local", e.currentTarget.value)}
        >
          {#each [null, ...q.choices] as cap (cap)}
            <option value={capValue(cap)}>{capLabel(cap)}</option>
          {/each}
        </select>
      </label>
      <label class="field">
        <span>Accès distant</span>
        <select
          value={capValue(q.remote)}
          onchange={(e) => change("remote", e.currentTarget.value)}
        >
          {#each [null, ...q.choices] as cap (cap)}
            <option value={capValue(cap)}>{capLabel(cap)}</option>
          {/each}
        </select>
      </label>
    </div>
    <p class="muted note">{addressNote(q.localAddress)} S'applique à la prochaine lecture.</p>
  {/if}
  {#if saveError}
    <p class="problem-text" role="alert">
      Le réglage n'a pas été enregistré : {saveError.message}
    </p>
  {/if}
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
  .note {
    font-size: 13px;
    max-width: 60ch;
  }
  .fields {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
  }
  .field {
    display: grid;
    gap: 6px;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  /* Glass control, as in the grid toolbar; the native list keeps the keyboard. */
  .field select {
    appearance: none;
    min-width: 220px;
    height: 38px;
    padding: 0 36px 0 14px;
    border: 0;
    border-radius: var(--radius-sm);
    background:
      url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23f5f5f7' stroke-width='2.5' stroke-linecap='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E")
        no-repeat right 14px center,
      var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
  }
  .field select:hover {
    background-color: var(--fill-glass-hover);
  }
  .field select option {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .field select:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
  .problem {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
  }
  .problem p,
  .problem-text {
    color: var(--color-danger);
  }
</style>
