<script lang="ts">
// One film or series in a library grid. Artwork comes ready from Rust (ADR 0005).
import type { MediaItem } from "../emby/types";
import { itemSubtitle } from "./pager";

interface Props {
  item: MediaItem;
}

let { item }: Props = $props();

let failed = $state(false);
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

<article class="card">
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
  <h3 class="title">{item.name}</h3>
  {#if subtitle}<p class="meta">{subtitle}</p>{/if}
</article>

<style>
  .card {
    min-width: 0;
  }
  .poster {
    position: relative;
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
    box-shadow: 0 8px 24px rgb(0 0 0 / 35%);
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
    background: rgb(11 13 17 / 80%);
    color: var(--color-text);
  }
  .badge.played {
    background: var(--color-success);
    color: #062014;
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
    margin: var(--space-2) 0 0;
    font-size: 14px;
    font-weight: 600;
    line-height: 1.3;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .meta {
    margin: 2px 0 0;
    font-size: 12px;
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
