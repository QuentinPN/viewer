<script lang="ts">
// Réglages › Accueil (ADR 0113): show or hide each home row and set their order,
// by drag and drop or with « Monter / Descendre ». Kept on this device for this
// server and user; the home applies it when it opens again.
import { tick, untrack } from "svelte";
import type { Library } from "../emby/types";
import {
  defaultRows,
  type HomeRowPrefs,
  isShown,
  moved,
  movedTo,
  orderedRows,
  readPrefs,
  savePrefs,
  toggled,
} from "../home/rows";

interface Props {
  libraries: Library[];
  storageKey: string;
}

let { libraries, storageKey }: Props = $props();

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

let prefs = $state<HomeRowPrefs>(
  untrack(() => readPrefs(storage(), storageKey)),
);
let dragging = $state<string | null>(null);
let over = $state<string | null>(null);
let announce = $state("");
let list = $state<HTMLOListElement | null>(null);

const rows = $derived(defaultRows(libraries));
const ordered = $derived(orderedRows(rows, prefs.order));

function apply(next: HomeRowPrefs) {
  prefs = next;
  savePrefs(storage(), storageKey, next);
}

function label(key: string) {
  return rows.find((r) => r.key === key)?.label ?? key;
}

async function move(key: string, delta: number, focusClass: string) {
  const next = moved(rows, prefs, key, delta);
  if (next === prefs) return;
  apply(next);
  const at = orderedRows(rows, next.order).findIndex((r) => r.key === key);
  announce = `${label(key)} : position ${at + 1} sur ${rows.length}.`;
  await tick();
  // Keep the focus on the moved row; at an edge, on its other button.
  const item = list?.querySelector<HTMLElement>(
    `li[data-key="${CSS.escape(key)}"]`,
  );
  const button =
    item?.querySelector<HTMLButtonElement>(
      `button.${focusClass}:not(:disabled)`,
    ) ?? item?.querySelector<HTMLButtonElement>("button.move:not(:disabled)");
  button?.focus();
}

function toggle(key: string) {
  apply(toggled(prefs, key));
  announce = `${label(key)} ${isShown(prefs, key) ? "affichée" : "masquée"}.`;
}

function drop(target: string) {
  if (dragging && dragging !== target) {
    apply(movedTo(rows, prefs, dragging, target));
  }
  dragging = null;
  over = null;
}

function reset() {
  apply({ order: [], hidden: [] });
  announce = "Ordre et rangées par défaut rétablis.";
}
</script>

<section class="panel" aria-labelledby="settings-home">
  <h2 id="settings-home">Accueil</h2>
  <p class="lead">
    Choisis les rangées de l'accueil et leur ordre : glisse-les, ou utilise Monter et
    Descendre. Une rangée masquée n'est pas demandée au serveur. Ces choix restent
    sur ce poste, pour ce compte.
  </p>

  <ol class="rows" bind:this={list}>
    {#each ordered as row, i (row.key)}
      <li
        data-key={row.key}
        class:dragging={dragging === row.key}
        class:over={over === row.key && dragging !== row.key}
        class:hidden-row={!isShown(prefs, row.key)}
        draggable="true"
        ondragstart={(e) => {
          dragging = row.key;
          e.dataTransfer?.setData("text/plain", row.key);
          if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
        }}
        ondragover={(e) => {
          if (!dragging) return;
          e.preventDefault();
          over = row.key;
        }}
        ondragleave={() => {
          if (over === row.key) over = null;
        }}
        ondrop={(e) => {
          e.preventDefault();
          drop(row.key);
        }}
        ondragend={() => {
          dragging = null;
          over = null;
        }}
      >
        <span class="grip" aria-hidden="true">⠿</span>
        <label class="check">
          <input
            type="checkbox"
            role="switch"
            checked={isShown(prefs, row.key)}
            onchange={() => toggle(row.key)}
          />
          <span class="name">{row.label}</span>
          <span class="state muted">{isShown(prefs, row.key) ? "Affichée" : "Masquée"}</span>
        </label>
        <span class="moves">
          <button
            type="button"
            class="move up"
            disabled={i === 0}
            aria-label="Monter {row.label}"
            onclick={() => void move(row.key, -1, "up")}>Monter</button
          >
          <button
            type="button"
            class="move down"
            disabled={i === ordered.length - 1}
            aria-label="Descendre {row.label}"
            onclick={() => void move(row.key, 1, "down")}>Descendre</button
          >
        </span>
      </li>
    {/each}
  </ol>

  <button type="button" class="secondary" onclick={reset}>Rétablir par défaut</button>
  <p class="muted note">S'applique au prochain retour à l'accueil.</p>
  <p class="sr-only" role="status">{announce}</p>
</section>

<style>
  /* Same panel as the other sections of Settings.svelte. */
  .panel {
    /* The track may shrink below the rows' widest name: names end with an ellipsis */
    grid-template-columns: minmax(0, 1fr);
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
  }
  .rows {
    list-style: none;
    margin: 0;
    padding: 0;
    width: 100%;
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-1);
  }
  .rows li {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-sm);
    background: var(--color-elevated);
    border: 1px solid transparent;
    transition:
      opacity var(--motion-base),
      border-color var(--motion-base);
  }
  .rows li.dragging {
    opacity: 0.5;
  }
  .rows li.over {
    border-color: var(--color-accent);
  }
  .rows li.hidden-row .name {
    color: var(--color-text-muted);
  }
  .grip {
    cursor: grab;
    color: var(--color-text-muted);
    user-select: none;
  }
  .check {
    flex: 1;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    cursor: pointer;
    min-width: 0;
  }
  .check input {
    margin: 0;
    width: 18px;
    height: 18px;
    accent-color: var(--color-accent);
    flex: none;
  }
  .check input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .state {
    font-size: 13px;
  }
  .moves {
    display: flex;
    gap: var(--space-1);
  }
  button {
    font: inherit;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
  }
  .move {
    padding: var(--space-1) var(--space-2);
    font-size: 13px;
    background: transparent;
    color: var(--color-text);
    border: 1px solid var(--color-surface);
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
  button:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  @media (prefers-reduced-motion: reduce) {
    .rows li {
      transition: none;
    }
  }
</style>
