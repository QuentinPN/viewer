<script lang="ts">
// « Regarder maintenant » on a Demander series: a season, then an episode,
// whose Stremio sources open next. The seasons are those of the request
// server; the episode titles are not known here, only their numbers.
import { onMount, tick } from "svelte";
import type { Season } from "../requests/types";
import "./dialog.css";
import {
  defaultSeason,
  episodeNumbers,
  pickableSeasons,
  seasonLabel,
} from "./episodes";

interface Props {
  seriesName: string;
  seasons: readonly Season[];
  /** The season shown first, e.g. that of the last episode picked. */
  initialSeason?: number | null;
  onClose: () => void;
  onPick: (pick: { season: number; episode: number }) => void;
}

let {
  seriesName,
  seasons,
  initialSeason = null,
  onClose,
  onPick,
}: Props = $props();

let dialog = $state<HTMLDialogElement | null>(null);
let seasonSelect = $state<HTMLSelectElement | null>(null);
let grid = $state<HTMLElement | null>(null);

const choices = $derived(pickableSeasons(seasons));
let chosen = $state<number | null>(null);
const current = $derived(choices.find((s) => s.seasonNumber === chosen));
const episodes = $derived(episodeNumbers(current?.episodeCount ?? 0));

function pick(episode: number) {
  if (chosen === null) return;
  onPick({ season: chosen, episode });
}

onMount(() => {
  const list = pickableSeasons(seasons);
  chosen = list.some((s) => s.seasonNumber === initialSeason)
    ? initialSeason
    : defaultSeason(list);
  dialog?.showModal();
  // The season list when there is a choice, else the first episode.
  void tick().then(() =>
    (seasonSelect ?? grid?.querySelector("button"))?.focus(),
  );
});
</script>

<dialog
  bind:this={dialog}
  class="st-dialog picker"
  aria-labelledby="episodes-title"
  aria-describedby="episodes-series"
  onclose={onClose}
  onkeydown={(e) => e.stopPropagation()}
>
  <div class="st-head head">
    <h2 id="episodes-title" class="st-title">Choisir un épisode</h2>
    <p id="episodes-series" class="muted">{seriesName}</p>
    {#if choices.length > 1}
      <label class="glass-field">
        <span>Saison</span>
        <select
          class="glass-select"
          bind:this={seasonSelect}
          value={chosen === null ? "" : String(chosen)}
          onchange={(e) => (chosen = Number(e.currentTarget.value))}
        >
          {#each choices as s (s.seasonNumber)}
            <option value={String(s.seasonNumber)}>{seasonLabel(s.seasonNumber)}</option>
          {/each}
        </select>
      </label>
    {:else if current}
      <p class="season">{seasonLabel(current.seasonNumber)}</p>
    {/if}
  </div>

  <div class="list">
    {#if choices.length === 0}
      <p class="muted">Aucun épisode connu pour cette série.</p>
    {:else}
      <ul
        class="grid"
        aria-label={current ? seasonLabel(current.seasonNumber) : undefined}
        bind:this={grid}
      >
        {#each episodes as n (n)}
          <li>
            <button type="button" class="episode" aria-label="Épisode {n}" onclick={() => pick(n)}
              >{n}</button
            >
          </li>
        {/each}
      </ul>
    {/if}
  </div>

  <div class="st-foot">
    <button type="button" class="st-btn" onclick={() => dialog?.close()}>Annuler</button>
  </div>
</dialog>

<style>
  .picker {
    width: min(560px, calc(100vw - 32px));
  }
  .head {
    justify-items: start;
  }
  .season {
    font-size: 14px;
    font-weight: 600;
  }
  .list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-1) var(--space-6);
  }
  .grid {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(56px, 1fr));
    gap: var(--space-2);
  }
  .episode {
    width: 100%;
    height: 44px;
    border: 0;
    border-radius: var(--radius-sm);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .episode:hover {
    background: var(--fill-glass-hover);
  }
  .muted {
    color: var(--color-text-muted);
  }
</style>
