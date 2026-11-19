<script lang="ts">
// Address of the Request server, shared by the tab's setup and Réglages ›
// Demandes. Rust normalizes and checks it (https, http on the local network only).
import "./requests.css";
import { isPlainHttp } from "./labels";

interface Props {
  /** The saved address, if any. */
  address: string | null;
  submitLabel: string;
  busy: boolean;
  onSubmit: (address: string) => void;
  /** Ids stay unique when two forms are on screen. */
  id: string;
}

let { address, submitLabel, busy, onSubmit, id }: Props = $props();

let value = $derived(address ?? "");
</script>

<form
  class="address"
  onsubmit={(e) => {
    e.preventDefault();
    if (value.trim() !== "") onSubmit(value.trim());
  }}
>
  <label class="ur-field" for={id}>
    <span>Adresse d'Request</span>
  </label>
  <div class="line">
    <input
      {id}
      class="ur-input"
      type="text"
      inputmode="url"
      autocomplete="off"
      spellcheck="false"
      placeholder="https://request.exemple.fr"
      bind:value
    />
    <button type="submit" class="ur-btn" disabled={busy || value.trim() === ""}>
      {submitLabel}
    </button>
  </div>
  {#if isPlainHttp(value)}
    <p class="ur-warning">
      Adresse non chiffrée : acceptée seulement sur le réseau local.
    </p>
  {/if}
</form>

<style>
  .address {
    display: grid;
    gap: var(--space-2);
    width: 100%;
    max-width: 520px;
  }
  .line {
    display: flex;
    gap: var(--space-2);
  }
  .line input {
    flex: 1;
  }
  p {
    margin: 0;
  }
</style>
