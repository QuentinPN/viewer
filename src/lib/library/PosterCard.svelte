<script lang="ts">
// One film or series in a library grid, opening its detail page. Artwork comes
// ready from Rust (ADR 0005). Right-click, the context-menu key or the "…"
// button open its menu (ADR 0100).
import type { MediaItem } from "../emby/types";
import { isMenuKey, type MenuContext, posterTarget } from "../menu/actions";
import MoreButton from "../menu/MoreButton.svelte";
import { boxOf, useCardMenu } from "../menu/menuState.svelte";
import type { Box } from "../menu/position";
import { itemSubtitle } from "./pager";

interface Props {
  item: MediaItem;
  onOpen: (item: MediaItem) => void;
  menuContext?: MenuContext;
}

let { item, onOpen, menuContext = "poster" }: Props = $props();

const menu = useCardMenu();
let card = $state<HTMLButtonElement | null>(null);
let more = $state<HTMLButtonElement | null>(null);
let failed = $state(false);
const target = $derived(menu ? posterTarget(item) : null);
const menuOpen = $derived(menu?.isOpenFor(card) ?? false);

function openMenu(anchor: Box) {
  if (menu && target && card) {
    menu.open({ target, context: menuContext, anchor, opener: card });
  }
}

function onkeydown(e: KeyboardEvent) {
  if (!target || !more || !isMenuKey(e)) return;
  e.preventDefault();
  openMenu(boxOf(more));
}

function oncontextmenu(e: MouseEvent) {
  if (!target) return;
  e.preventDefault();
  // The context-menu key also sends this event: the menu is already open.
  if (!menuOpen)
    openMenu({ left: e.clientX, top: e.clientY, width: 0, height: 0 });
}
const subtitle = $derived(itemSubtitle(item));
const initials = $derived(
  item.name
    .split(/\s+/)
    .filter((w) => /\p{L}|\p{N}/u.test(w))
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join(""),
);
</script>

<div class="menu-holder" class:has-menu={target !== null}>
  <button
    bind:this={card}
    type="button"
    class="card"
    data-item-id={item.id}
    aria-keyshortcuts={target ? "Shift+F10" : undefined}
    onclick={() => onOpen(item)}
    {onkeydown}
    {oncontextmenu}
  >
    <div class="poster">
      {#if item.poster && !failed}
        <img
          src={item.poster.src}
          srcset={item.poster.srcset}
          sizes="(min-width: 1600px) 220px, 180px"
          alt=""
          loading="lazy"
          decoding="async"
          onerror={() => (failed = true)}
        />
      {:else}
        <span class="placeholder" aria-hidden="true">{initials}</span>
      {/if}
      {#if item.played}
        <span class="badge played" title="Déjà vu">
          <svg viewBox="0 0 16 16" aria-hidden="true"
            ><path d="M3 8.5 6.5 12 13 4.5" /></svg
          >
          <span class="sr-only">Déjà vu</span>
        </span>
      {:else if item.unplayedCount}
        <span class="badge count" title="Épisodes non vus">
          {item.unplayedCount}<span class="sr-only"> épisodes non vus</span>
        </span>
      {/if}
    </div>
    <span class="title">{item.name}</span>
    {#if subtitle}<span class="meta">{subtitle}</span>{/if}
  </button>
  {#if target}
    <MoreButton
      bind:button={more}
      title={item.name}
      expanded={menuOpen}
      onclick={() => more && openMenu(boxOf(more))}
    />
  {/if}
</div>

<style>
  .menu-holder {
    position: relative;
    min-width: 0;
  }
  /* The "…" button takes the badge's corner while it shows. */
  .has-menu:hover .badge,
  .has-menu:focus-within .badge {
    opacity: 0;
  }
  .card {
    min-width: 0;
    width: 100%;
    display: block;
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
    border-radius: var(--radius-md);
  }
  /* Focus lifts the artwork (ADR 0031); the accent ring marks the keyboard focus. */
  .card:focus-visible {
    outline: none;
  }
  .card:focus-visible .poster {
    outline: 2px solid var(--color-accent);
    outline-offset: 3px;
  }
  .card:hover .poster,
  .card:focus-visible .poster {
    transform: scale(var(--lift-scale));
    box-shadow: var(--shadow-lift);
  }
  .poster {
    position: relative;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
    box-shadow: 0 6px 18px rgb(0 0 0 / 0.4);
    transform-origin: center bottom;
    transition:
      transform var(--motion-lift) var(--ease-spring),
      box-shadow var(--motion-lift) ease-out;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .placeholder {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    font-size: 32px;
    font-weight: 600;
    color: var(--color-text-muted);
  }
  .badge {
    position: absolute;
    top: var(--space-2);
    right: var(--space-2);
    min-width: 24px;
    height: 24px;
    padding: 0 var(--space-1);
    border-radius: 12px;
    display: grid;
    place-items: center;
    font-size: 12px;
    font-weight: 700;
    background: rgb(0 0 0 / 0.55);
    backdrop-filter: blur(12px);
    color: #fff;
  }
  .badge svg {
    width: 14px;
    height: 14px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .title {
    margin: 10px 0 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.3;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .meta {
    display: block;
    margin: 2px 0 0;
    font-size: 13px;
    color: var(--color-text-muted);
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
</style>
