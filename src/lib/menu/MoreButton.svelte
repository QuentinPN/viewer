<script lang="ts">
// The "…" button of a card (ADR 0100): over its artwork's top-right corner,
// shown while the card is hovered or focused, and reached by Tab after it.
interface Props {
  title: string;
  expanded: boolean;
  button?: HTMLButtonElement | null;
  onclick: () => void;
}

let { title, expanded, button = $bindable(null), onclick }: Props = $props();
</script>

<button
  bind:this={button}
  type="button"
  class="more"
  aria-label="Plus d'actions pour « {title} »"
  title="Plus d'actions"
  aria-haspopup="menu"
  aria-expanded={expanded}
  {onclick}
>
  <svg viewBox="0 0 16 16" aria-hidden="true"
    ><circle cx="3.5" cy="8" r="1.4" /><circle cx="8" cy="8" r="1.4" /><circle
      cx="12.5"
      cy="8"
      r="1.4"
    /></svg
  >
</button>

<style>
  .more {
    position: absolute;
    z-index: 1;
    top: var(--space-2);
    right: var(--space-2);
    width: 30px;
    height: 30px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: rgb(0 0 0 / 60%);
    backdrop-filter: blur(12px);
    color: #fff;
    cursor: pointer;
    opacity: 0;
    transition:
      opacity var(--motion-fast),
      background var(--motion-fast);
  }
  :global(.menu-holder:hover) > .more,
  :global(.menu-holder:focus-within) > .more,
  .more[aria-expanded="true"] {
    opacity: 1;
  }
  .more:hover {
    background: rgb(0 0 0 / 80%);
  }
  .more:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  svg {
    width: 16px;
    height: 16px;
    fill: currentColor;
  }
</style>
