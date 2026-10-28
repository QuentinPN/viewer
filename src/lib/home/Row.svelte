<script lang="ts">
// One horizontal row of the home page: a heading, an optional action ("Tout voir"),
// buttons scrolling by a window width (no hover-only control), and Left/Right
// between the cards.
import type { Snippet } from "svelte";

interface Props {
  title: string;
  /** Heading id; labels the section and its list. */
  id: string;
  /** 16:9 cards instead of 2:3 posters. */
  wide?: boolean;
  /** Shown after the title, e.g. "Tout voir". */
  action?: Snippet;
  children: Snippet;
}

let { title, id, wide = false, action, children }: Props = $props();

let list = $state<HTMLElement | null>(null);
let atStart = $state(true);
let atEnd = $state(true);

function update() {
  if (!list) return;
  atStart = list.scrollLeft <= 1;
  atEnd = list.scrollLeft + list.clientWidth >= list.scrollWidth - 1;
}

function page(direction: 1 | -1) {
  if (!list) return;
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  list.scrollBy({
    left: direction * list.clientWidth * 0.9,
    behavior: reduced ? "auto" : "smooth",
  });
}

/** Left/Right move between the cards of the row; Tab leaves it as usual. */
function onkeydown(e: KeyboardEvent) {
  if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
  const cards = [...(list?.querySelectorAll<HTMLElement>("button.card") ?? [])];
  const at = cards.indexOf(document.activeElement as HTMLElement);
  if (at < 0) return;
  const next = cards[at + (e.key === "ArrowRight" ? 1 : -1)];
  if (next) {
    e.preventDefault();
    next.focus();
  }
}

$effect(() => {
  if (!list) return;
  const target = list;
  // Also fires when cards arrive: the content box of the list grows.
  const observer = new ResizeObserver(update);
  observer.observe(target);
  for (const child of target.children) observer.observe(child);
  const mutations = new MutationObserver(update);
  mutations.observe(target, { childList: true });
  // Delegated: the keys only move focus between the card buttons.
  target.addEventListener("keydown", onkeydown);
  return () => {
    observer.disconnect();
    mutations.disconnect();
    target.removeEventListener("keydown", onkeydown);
  };
});
</script>

<section class="row" aria-labelledby={id}>
  <header>
    <h2 {id}>{title}</h2>
    {@render action?.()}
    <div class="arrows">
      <button
        type="button"
        class="arrow"
        aria-label="Faire défiler « {title} » vers la gauche"
        disabled={atStart}
        onclick={() => page(-1)}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
      </button>
      <button
        type="button"
        class="arrow"
        aria-label="Faire défiler « {title} » vers la droite"
        disabled={atEnd}
        onclick={() => page(1)}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m6 3 5 5-5 5" /></svg>
      </button>
    </div>
  </header>
  <ul
    class="cards"
    class:wide
    aria-labelledby={id}
    bind:this={list}
    onscroll={update}
  >
    {@render children()}
  </ul>
</section>

<style>
  .row {
    margin-bottom: 32px;
  }
  header {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-2);
  }
  h2 {
    margin: 0;
    font-size: 20px;
    letter-spacing: -0.01em;
  }
  .arrows {
    margin-left: auto;
    display: flex;
    gap: var(--space-1);
  }
  .arrow {
    width: 32px;
    height: 32px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: var(--color-surface);
    color: var(--color-text);
    cursor: pointer;
  }
  .arrow:hover:not(:disabled) {
    background: var(--color-elevated);
  }
  .arrow:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .arrow:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .arrow svg {
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .cards {
    list-style: none;
    /* Room for the focus outline and the lift of the hovered card. */
    margin: 0 -8px;
    padding: 8px 8px 12px;
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 150px;
    gap: var(--space-4);
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scroll-snap-type: x proximity;
    scroll-padding-inline: 8px;
    /* The arrow buttons, the keys and Maj + molette scroll the row. */
    scrollbar-width: none;
  }
  .cards.wide {
    grid-auto-columns: 280px;
  }
  @media (min-width: 1600px) {
    .cards {
      grid-auto-columns: 180px;
    }
    .cards.wide {
      grid-auto-columns: 340px;
    }
  }
  .cards > :global(li) {
    min-width: 0;
    scroll-snap-align: start;
  }
</style>
