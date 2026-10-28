<script lang="ts">
// 16:9 card of "Reprendre" and "À suivre": activating it plays the film or episode
// at once, from the resume point when there is one (ADR 0010).
import type { WatchItem } from "../emby/types";
import { watchAction, watchMeta, watchTitle } from "./format";

interface Props {
  item: WatchItem;
  /** This card's playback is being negotiated. */
  busy: boolean;
  /** Another playback is starting: activating does nothing. */
  disabled: boolean;
  onPlay: (item: WatchItem) => void;
}

let { item, busy, disabled, onPlay }: Props = $props();

let failed = $state(false);
const progress = $derived(item.resume?.fraction ?? null);
</script>

<button
  type="button"
  class="card"
  data-item-id={item.id}
  aria-label={watchAction(item)}
  aria-disabled={disabled}
  aria-busy={busy}
  onclick={() => !disabled && onPlay(item)}
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
      <span class="placeholder" aria-hidden="true">{watchTitle(item)}</span>
    {/if}
    <span class="play" class:visible={busy} aria-hidden="true">
      {#if busy}
        <span class="spinner"></span>
      {:else}
        <svg viewBox="0 0 24 24"><path d="M8 5.5v13l11-6.5z" /></svg>
      {/if}
    </span>
    {#if progress !== null}
      <span class="progress" aria-hidden="true"
        ><span style:width="{Math.max(progress * 100, 2)}%"></span></span
      >
    {/if}
  </div>
  <span class="title" aria-hidden="true">{watchTitle(item)}</span>
  <span class="meta" aria-hidden="true">{watchMeta(item)}</span>
</button>

<style>
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
  .card:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 4px;
  }
  .frame {
    position: relative;
    aspect-ratio: 16 / 9;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
    box-shadow: 0 8px 24px rgb(0 0 0 / 35%);
    transition:
      transform var(--motion-base) ease-out,
      box-shadow var(--motion-base) ease-out;
  }
  .card:hover .frame,
  .card:focus-visible .frame {
    transform: translateY(-3px);
    box-shadow: 0 14px 32px rgb(0 0 0 / 45%);
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
    background: rgb(11 13 17 / 45%);
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
    background: rgb(245 246 248 / 92%);
    fill: #0b0d11;
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
    left: var(--space-2);
    right: var(--space-2);
    bottom: var(--space-2);
    height: 4px;
    border-radius: 2px;
    background: rgb(245 246 248 / 30%);
    overflow: hidden;
  }
  .progress span {
    display: block;
    height: 100%;
    background: var(--color-accent);
  }
  .title {
    display: block;
    margin-top: var(--space-2);
    font-size: 14px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta {
    display: block;
    margin-top: 2px;
    font-size: 12px;
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
