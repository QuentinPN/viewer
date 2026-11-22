<script lang="ts">
// Update ready (DESIGN.md « Mise à jour prête »): a discreet glass pill at
// the top right, and a frosted 440 px card with the release notes. Never shown
// during playback; the update itself is downloaded and verified on the Rust side.
import { onMount, tick } from "svelte";
import { updaterApi } from "./api";
import { formatNotes } from "./notes";
import { createUpdateStore } from "./store";

interface Props {
  /** False during playback: the pill and card are not rendered. */
  visible: boolean;
}

let { visible }: Props = $props();

const store = createUpdateStore(updaterApi);
let open = $state(false);
let pillButton = $state<HTMLButtonElement | null>(null);
let card = $state<HTMLDivElement | null>(null);
let laterButton = $state<HTMLButtonElement | null>(null);

const notes = $derived($store.ready ? formatNotes($store.ready.notes) : []);

onMount(() => {
  void store.start();
  return () => store.dispose();
});

$effect(() => {
  if (!visible) open = false;
});

async function openCard() {
  open = true;
  await tick();
  laterButton?.focus({ preventScroll: true });
}

async function closeCard() {
  open = false;
  await tick();
  pillButton?.focus({ preventScroll: true });
}

/** From the pill: a failure opens the card, which shows the error. */
async function restartFromPill() {
  await store.install();
  if ($store.error) await openCard();
}

function onCardKey(e: KeyboardEvent) {
  if (e.key === "Escape") {
    e.preventDefault();
    e.stopPropagation();
    void closeCard();
    return;
  }
  if (e.key !== "Tab" || !card) return;
  const items = [
    ...card.querySelectorAll<HTMLElement>("button:not(:disabled)"),
  ];
  const first = items[0];
  const last = items[items.length - 1];
  if (!first || !last) return;
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault();
    first.focus();
  }
}
</script>

{#if visible && $store.ready}
  {@const ready = $store.ready}
  <div class="pill" role="group" aria-label="Mise à jour">
    <button
      type="button"
      class="pill-open"
      bind:this={pillButton}
      aria-haspopup="dialog"
      aria-expanded={open}
      onclick={openCard}
    >
      <span class="dot" aria-hidden="true"></span>
      <span class="pill-label">Mise à jour prête</span>
    </button>
    <button
      type="button"
      class="pill-restart"
      disabled={$store.installing}
      onclick={() => void restartFromPill()}
    >
      {$store.installing ? "Redémarrage…" : "Redémarrer"}
    </button>
  </div>

  {#if open}
    <!-- Pointer only: the keyboard closes the card with Escape or « Plus tard ». -->
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <div class="scrim" role="presentation" onclick={closeCard}></div>
    <div
      class="card"
      bind:this={card}
      role="dialog"
      aria-modal="true"
      aria-labelledby="update-title"
      aria-describedby="update-desc"
      tabindex="-1"
      onkeydown={onCardKey}
    >
      <div class="tile" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="none">
          <path
            d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19h14"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>
      <h2 id="update-title">Viewer {ready.version} est prête</h2>
      <p id="update-desc" class="lead">
        Installée en quelques secondes, l'app redémarre.
      </p>
      {#if notes.length > 0}
        <div class="notes">
          {#each notes as line, i (i)}
            {#if line.kind === "heading"}
              <p class="note-heading">{line.text}</p>
            {:else if line.kind === "bullet"}
              <p class="note-bullet">{line.text}</p>
            {:else}
              <p>{line.text}</p>
            {/if}
          {/each}
        </div>
      {/if}
      {#if $store.error}
        <p class="error" role="alert">{$store.error}</p>
      {/if}
      <div class="actions">
        <button
          type="button"
          class="secondary"
          bind:this={laterButton}
          onclick={closeCard}
        >
          Plus tard
        </button>
        <button
          type="button"
          disabled={$store.installing}
          onclick={() => void store.install()}
        >
          {$store.installing ? "Redémarrage…" : "Redémarrer maintenant"}
        </button>
      </div>
    </div>
  {/if}
{/if}

<style>
  .pill {
    position: fixed;
    top: calc(var(--titlebar-height) + var(--space-2));
    right: var(--space-4);
    z-index: 30;
    display: flex;
    align-items: center;
    gap: var(--space-1);
    padding: 5px;
    border-radius: var(--radius-pill);
    background: var(--material);
    backdrop-filter: var(--material-blur);
    border: 1px solid var(--separator);
    box-shadow: 0 10px 30px rgb(0 0 0 / 0.35);
  }
  button {
    font: inherit;
    font-weight: 600;
    border: 0;
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  .pill-open {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    height: 32px;
    padding: 0 var(--space-3) 0 12px;
    border-radius: var(--radius-pill);
    background: transparent;
    color: var(--color-text);
    font-size: 13px;
  }
  .pill-open:hover {
    background: rgb(255 255 255 / 8%);
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color-accent);
  }
  .pill-restart {
    height: 32px;
    padding: 0 14px;
    border-radius: var(--radius-pill);
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-size: 13px;
  }
  .pill-restart:hover:not(:disabled),
  .actions button:not(.secondary):hover:not(:disabled) {
    background: #fff;
  }
  /* Narrow windows: the centered navigation needs the room. */
  @media (max-width: 1100px) {
    .pill-label {
      display: none;
    }
    .pill-open {
      padding: 0 12px;
    }
  }
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 40;
    background: rgb(0 0 0 / 60%);
    backdrop-filter: blur(12px);
  }
  .card {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    z-index: 41;
    box-sizing: border-box;
    width: min(440px, calc(100% - 2 * var(--space-4)));
    max-height: calc(100% - var(--titlebar-height) - 2 * var(--space-4));
    overflow-y: auto;
    padding: 32px;
    border-radius: 28px;
    background: rgb(36 36 42 / 68%);
    backdrop-filter: blur(40px) saturate(180%);
    border: 1px solid rgb(255 255 255 / 10%);
    box-shadow: 0 40px 80px rgb(0 0 0 / 60%);
    animation: rise var(--motion-base) ease-out;
  }
  .card:focus {
    outline: none;
  }
  @keyframes rise {
    from {
      opacity: 0;
      transform: translate(-50%, calc(-50% + 8px));
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .card {
      animation: none;
    }
  }
  .tile {
    width: 56px;
    height: 56px;
    margin: 0 auto var(--space-4);
    display: grid;
    place-items: center;
    border-radius: 16px;
    background: var(--fill-glass);
    color: var(--color-accent);
  }
  h2 {
    margin: 0;
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.02em;
    text-align: center;
  }
  .lead {
    margin: 6px 0 var(--space-4);
    font-size: 15px;
    line-height: 1.5;
    color: var(--color-text-secondary);
    text-align: center;
  }
  .notes {
    max-height: 200px;
    overflow-y: auto;
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    background: rgb(0 0 0 / 30%);
    border: 1px solid var(--separator);
    font-size: 14px;
    line-height: 1.5;
  }
  .notes p {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .notes p + p {
    margin-top: 4px;
  }
  .note-heading {
    font-weight: 600;
  }
  .note-bullet {
    position: relative;
    padding-left: 14px;
    color: var(--color-text-secondary);
  }
  .note-bullet::before {
    content: "";
    position: absolute;
    left: 2px;
    top: 0.6em;
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: var(--color-text-secondary);
  }
  .error {
    margin: var(--space-3) 0 0;
    color: var(--color-danger);
    font-size: 14px;
    text-align: center;
  }
  .actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-2);
    margin-top: var(--space-6);
  }
  .actions button {
    height: 48px;
    padding: 0 var(--space-4);
    border-radius: var(--radius-md);
    background: var(--color-primary);
    color: var(--color-on-primary);
    font-size: 15px;
  }
  .actions button.secondary {
    background: var(--fill-glass);
    color: var(--color-text);
  }
  .actions button.secondary:hover {
    background: var(--fill-glass-hover);
  }
</style>
