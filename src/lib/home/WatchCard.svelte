<script lang="ts">
// 16:9 card of "Reprendre" and "À suivre": activating it plays the film or episode
// at once, from the resume point when there is one. An episode found by
// the search opens its page instead, its own title first.
// Right-click, the context-menu key or the "…" button open its menu.
import type { WatchItem } from "../emby/types";
import { isMenuKey, type MenuContext, watchTarget } from "../menu/actions";
import MoreButton from "../menu/MoreButton.svelte";
import { boxOf, useCardMenu } from "../menu/menuState.svelte";
import type { Box } from "../menu/position";
import {
  foundMeta,
  openAction,
  watchAction,
  watchMeta,
  watchTitle,
} from "./format";

type Props = {
  item: WatchItem;
  /** Default: "nextUp" for a card that plays, "search" for one that opens. */
  menuContext?: MenuContext;
  /** "Reprendre": the menu's "Marquer comme vu" takes the card out. */
  onMarkPlayed?: (item: WatchItem) => void;
  onRemoveFromResume?: (item: WatchItem) => void;
} & (
  | {
      /** This card's playback is being negotiated. */
      busy: boolean;
      /** Another playback is starting: activating does nothing. */
      disabled: boolean;
      onPlay: (item: WatchItem) => void;
      onOpen?: never;
    }
  | {
      onOpen: (item: WatchItem) => void;
      busy?: never;
      disabled?: never;
      onPlay?: never;
    }
);

let {
  item,
  busy = false,
  disabled = false,
  onPlay,
  onOpen,
  menuContext,
  onMarkPlayed,
  onRemoveFromResume,
}: Props = $props();

const menu = useCardMenu();
let card = $state<HTMLButtonElement | null>(null);
let more = $state<HTMLButtonElement | null>(null);
let failed = $state(false);
const target = $derived(menu ? watchTarget(item) : null);
const menuOpen = $derived(menu?.isOpenFor(card) ?? false);
const progress = $derived(item.resume?.fraction ?? null);
const title = $derived(onOpen ? item.name : watchTitle(item));
const meta = $derived(onOpen ? foundMeta(item) : watchMeta(item));

function activate() {
  if (onOpen) onOpen(item);
  else if (!disabled) onPlay?.(item);
}

function openMenu(anchor: Box) {
  if (!menu || !target || !card) return;
  menu.open({
    target,
    context: menuContext ?? (onOpen ? "search" : "nextUp"),
    anchor,
    opener: card,
    // A home card plays as when activated, with its spinner.
    play: onPlay
      ? () => {
          if (!disabled) onPlay(item);
        }
      : undefined,
    markPlayed: onMarkPlayed ? () => onMarkPlayed(item) : undefined,
    removeFromResume: onRemoveFromResume
      ? () => onRemoveFromResume(item)
      : undefined,
  });
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
  if (!menuOpen) {
    openMenu({ left: e.clientX, top: e.clientY, width: 0, height: 0 });
  }
}
</script>

<div class="menu-holder">
  <button
    bind:this={card}
    type="button"
    class="card"
    data-item-id={item.id}
    aria-keyshortcuts={target ? "Shift+F10" : undefined}
    aria-label={onOpen ? openAction(item) : watchAction(item)}
    aria-disabled={onOpen ? undefined : disabled}
    aria-busy={onOpen ? undefined : busy}
    onclick={activate}
    {onkeydown}
    {oncontextmenu}
  >
    <div class="frame">
      {#if item.image && !failed}
        <img
          src={item.image.src}
          srcset={item.image.srcset}
          sizes="(min-width: 1600px) 340px, 280px"
          alt=""
          loading="lazy"
          decoding="async"
          onerror={() => (failed = true)}
        />
      {:else}
        <span class="placeholder" aria-hidden="true">{title}</span>
      {/if}
      {#if !onOpen}
        <span class="play" class:visible={busy} aria-hidden="true">
          {#if busy}
            <span class="spinner"></span>
          {:else}
            <svg viewBox="0 0 24 24"><path d="M8 5.5v13l11-6.5z" /></svg>
          {/if}
        </span>
      {/if}
      {#if progress !== null}
        <span class="progress" aria-hidden="true"
          ><span style:width="{Math.max(progress * 100, 2)}%"></span></span
        >
      {/if}
    </div>
    <span class="title" aria-hidden="true">{title}</span>
    <span class="meta" aria-hidden="true">{meta}</span>
  </button>
  {#if target}
    <MoreButton
      bind:button={more}
      {title}
      expanded={menuOpen}
      onclick={() => more && openMenu(boxOf(more))}
    />
  {/if}
</div>

<style>
  .menu-holder {
    position: relative;
  }
  .card {
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
  .card[aria-disabled="true"] {
    cursor: progress;
  }
  /* Focus lifts the artwork; the accent ring marks the keyboard focus. */
  .card:focus-visible {
    outline: none;
  }
  .card:focus-visible .frame {
    outline: 2px solid var(--color-accent);
    outline-offset: 3px;
  }
  .frame {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
    box-shadow: 0 6px 18px rgb(0 0 0 / 0.4);
    transform-origin: center bottom;
    transition:
      transform var(--motion-lift) var(--ease-spring),
      box-shadow var(--motion-lift) ease-out;
  }
  .card:hover .frame,
  .card:focus-visible .frame {
    transform: scale(var(--lift-scale));
    box-shadow: var(--shadow-lift);
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
    padding: var(--space-4);
    text-align: center;
    font-weight: 600;
    color: var(--color-text-muted);
  }
  .play {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.35);
    opacity: 0;
    transition: opacity var(--motion-fast);
  }
  .card:hover .play,
  .card:focus-visible .play,
  .play.visible {
    opacity: 1;
  }
  .play svg {
    width: 44px;
    height: 44px;
    padding: 10px;
    border-radius: 50%;
    background: rgb(245 245 247 / 0.92);
    fill: #000;
  }
  .spinner {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 3px solid rgb(245 246 248 / 35%);
    border-top-color: var(--color-text);
    animation: spin 0.9s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(1turn);
    }
  }
  .progress {
    position: absolute;
    left: 14px;
    right: 14px;
    bottom: 10px;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.3);
    overflow: hidden;
  }
  .progress span {
    display: block;
    height: 100%;
    background: #fff;
  }
  .title {
    display: block;
    margin-top: 10px;
    font-size: 15px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta {
    display: block;
    margin-top: 2px;
    font-size: 13px;
    color: var(--color-text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation-duration: 2.4s;
    }
  }
</style>
