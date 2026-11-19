<script lang="ts">
// « Demander » (Fiche A): a centred frosted window. A film asks for a
// confirmation; a series lists its seasons, only the requestable ones can be
// ticked. The quota shows before sending; the server's errors as they come.
import { onMount } from "svelte";
import "./requests.css";
import { requestsApi, toRequestsError } from "./api";
import { mediaStatusText, quotaUsageLine, statusTone } from "./labels";
import { isoDay, isSeasonRequestable, seasonsLabel } from "./seasons";
import type { MediaDetails, RequestQuota, RequestsError } from "./types";

interface Props {
  details: MediaDetails;
  onClose: () => void;
  /** The request was taken: the page reloads the title's status. */
  onDone: () => void;
}

let { details, onClose, onDone }: Props = $props();

let dialog = $state<HTMLDialogElement | null>(null);
let quota = $state<RequestQuota | null>(null);
let error = $state<RequestsError | null>(null);
let sending = $state(false);
const today = isoDay();
const seasons = $derived(details.seasons.filter((s) => s.seasonNumber > 0));
let chosen = $state<number[]>([]);

const isMovie = $derived(details.type === "movie");
const canSend = $derived(!sending && (isMovie || chosen.length > 0));

function toggle(n: number, on: boolean) {
  chosen = on
    ? [...chosen, n].sort((a, b) => a - b)
    : chosen.filter((c) => c !== n);
}

async function send() {
  if (!canSend) return;
  sending = true;
  error = null;
  try {
    await requestsApi.request(
      isMovie
        ? { type: "movie", tmdbId: details.tmdbId }
        : { type: "tv", tmdbId: details.tmdbId, seasons: chosen },
    );
    onDone();
  } catch (e) {
    error = toRequestsError(e);
  } finally {
    sending = false;
  }
}

onMount(() => {
  chosen = details.seasons
    .filter((s) => isSeasonRequestable(s, today))
    .map((s) => s.seasonNumber);
  dialog?.showModal();
  requestsApi.quota().then(
    (q) => (quota = q),
    () => {},
  );
});
</script>

<dialog
  bind:this={dialog}
  class="dialog"
  aria-labelledby="ur-dialog-title"
  onclose={onClose}
>
  <form
    method="dialog"
    onsubmit={(e) => {
      e.preventDefault();
      void send();
    }}
  >
    <h2 id="ur-dialog-title">
      {isMovie ? `Demander « ${details.title} » ?` : `Demander « ${details.title} »`}
    </h2>

    {#if isMovie}
      <p class="ur-muted">
        Le film sera ajouté à la bibliothèque Emby dès qu'il sera disponible.
      </p>
    {:else}
      <fieldset>
        <legend>Saisons</legend>
        {#each seasons as s (s.seasonNumber)}
          {@const open = isSeasonRequestable(s, today)}
          <label class="season" class:off={!open}>
            <input
              type="checkbox"
              disabled={!open}
              checked={chosen.includes(s.seasonNumber)}
              onchange={(e) => toggle(s.seasonNumber, e.currentTarget.checked)}
            />
            <span>Saison {s.seasonNumber}</span>
            <span class="ur-muted small">
              {s.episodeCount} épisode{s.episodeCount > 1 ? "s" : ""}
            </span>
            {#if s.status}
              <span class="status ur-tone-{statusTone(s.status)}">
                {mediaStatusText(s.status, s.progress)}
              </span>
            {:else if !open}
              <span class="status ur-muted">Pas encore diffusée</span>
            {/if}
          </label>
        {/each}
      </fieldset>
    {/if}

    {#if quota && !quota.exempt}
      <p class="quota">
        Quota : {quotaUsageLine(quota, isMovie ? "movie" : "tv")}
      </p>
    {/if}

    {#if error}<p class="ur-error" role="alert">{error.message}</p>{/if}

    <div class="actions">
      <button type="button" class="ur-btn" onclick={() => dialog?.close()}>
        Annuler
      </button>
      <button type="submit" class="ur-btn primary" disabled={!canSend}>
        {sending ? "Envoi…" : isMovie ? "Demander" : seasonsLabel(chosen.length)}
      </button>
    </div>
  </form>
</dialog>

<style>
  .dialog {
    width: min(480px, calc(100vw - 32px));
    max-height: calc(100vh - 64px);
    padding: 28px;
    border: 1px solid var(--separator);
    border-radius: var(--radius-lg);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    color: var(--color-text);
    font: inherit;
  }
  .dialog::backdrop {
    background: rgb(0 0 0 / 0.55);
    backdrop-filter: blur(6px);
  }
  form {
    display: grid;
    gap: var(--space-4);
  }
  h2 {
    margin: 0;
    font-size: 22px;
  }
  p {
    margin: 0;
  }
  fieldset {
    margin: 0;
    padding: 0;
    border: 0;
    display: grid;
    gap: 2px;
    max-height: 320px;
    overflow-y: auto;
  }
  legend {
    padding: 0;
    margin-bottom: var(--space-2);
    font-size: 13px;
    color: var(--color-text-muted);
  }
  .season {
    display: grid;
    grid-template-columns: auto auto 1fr auto;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-sm);
    cursor: pointer;
  }
  .season:hover:not(.off) {
    background: rgb(255 255 255 / 0.06);
  }
  .season.off {
    opacity: 0.5;
    cursor: default;
  }
  input[type="checkbox"] {
    width: 18px;
    height: 18px;
    accent-color: var(--color-accent);
  }
  input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .small {
    font-size: 13px;
  }
  .status {
    font-size: 13px;
    font-weight: 600;
  }
  .quota {
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-sm);
    background: rgb(255 255 255 / 0.06);
    font-size: 14px;
    color: var(--color-text-secondary);
  }
  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
  }
</style>
