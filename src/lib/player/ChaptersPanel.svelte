<script lang="ts">
// "Chapitres" panel of the player (ADR 0034): one row per chapter with its start,
// the playing one marked. A row seeks there. ↑ ↓, Début and Fin move between rows
// and stay in the list, so the player's keys do not change the volume meanwhile.
import type { Chapter } from "../emby/types";
import { formatTime } from "./format";

interface Props {
  chapters: Chapter[];
  /** Index of the chapter playing, -1 before the first one. */
  current: number;
  onSelect: (start: number) => void;
}

let { chapters, current, onSelect }: Props = $props();

let list = $state<HTMLOListElement | null>(null);

function onKeydown(e: KeyboardEvent, at: number) {
  const rows = [...(list?.querySelectorAll<HTMLButtonElement>("button") ?? [])];
  const to =
    e.key === "ArrowDown"
      ? Math.min(rows.length - 1, at + 1)
      : e.key === "ArrowUp"
        ? Math.max(0, at - 1)
        : e.key === "Home"
          ? 0
          : e.key === "End"
            ? rows.length - 1
            : null;
  if (to !== null) {
    e.preventDefault();
    e.stopPropagation();
    rows[to]?.focus();
  } else if (e.key === " " || e.key === "Enter") {
    // The row's own click, not the player's pause or skip.
    e.stopPropagation();
  }
}
</script>

<h2 class="title">Chapitres</h2>
<ol class="rows" bind:this={list}>
  {#each chapters as chapter, i (chapter.start)}
    <li>
      <button
        type="button"
        class="row"
        aria-current={i === current ? "true" : undefined}
        onclick={() => onSelect(chapter.start)}
        onkeydown={(e) => onKeydown(e, i)}
      >
        <span class="name">{chapter.name}</span>
        <span class="start">{formatTime(chapter.start)}</span>
      </button>
    </li>
  {/each}
</ol>

<style>
  .title {
    padding: 0 var(--space-2) var(--space-2);
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-secondary);
  }
  .rows {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 2px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    width: 100%;
    min-height: 40px;
    padding: 0 10px;
    border: 0;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text);
    font: inherit;
    font-size: 15px;
    text-align: left;
    cursor: pointer;
  }
  .row:hover {
    background: rgb(255 255 255 / 8%);
  }
  .row[aria-current="true"] {
    background: rgb(235 235 245 / 14%);
  }
  .row:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: -2px;
  }
  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .row[aria-current="true"] .name {
    font-weight: 600;
  }
  .start {
    flex: none;
    font-size: 13px;
    font-variant-numeric: tabular-nums;
    color: var(--color-text-secondary);
  }
</style>
