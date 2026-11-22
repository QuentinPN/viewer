<script lang="ts">
// One playlist of the home row « Playlists »: opens its page.
// Artwork comes ready from Rust.
import type { PlaylistCard } from "../emby/types";
import { titlesLabel } from "./playlist";

interface Props {
  playlist: PlaylistCard;
  onOpen: (playlist: PlaylistCard) => void;
}

let { playlist, onOpen }: Props = $props();

let failed = $state(false);
const count = $derived(
  playlist.count == null ? null : titlesLabel(playlist.count),
);
</script>

<button
  type="button"
  class="card"
  data-playlist-id={playlist.id}
  onclick={() => onOpen(playlist)}
>
  <div class="poster">
    {#if playlist.poster && !failed}
      <img
        src={playlist.poster.src}
        srcset={playlist.poster.srcset}
        sizes="(min-width: 1600px) 220px, 180px"
        alt=""
        loading="lazy"
        decoding="async"
        onerror={() => (failed = true)}
      />
    {:else}
      <span class="placeholder" aria-hidden="true">
        <svg viewBox="0 0 24 24"
          ><path d="M4 6h12M4 11h12M4 16h7M16 14v6l5-3z" /></svg
        >
      </span>
    {/if}
  </div>
  <span class="title">{playlist.name}</span>
  {#if count}<span class="meta">{count}</span>{/if}
</button>

<style>
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
  /* Same lift and focus ring as the posters. */
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
  @media (prefers-reduced-motion: reduce) {
    .poster {
      transition: none;
    }
    .card:hover .poster,
    .card:focus-visible .poster {
      transform: none;
    }
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
  }
  .placeholder svg {
    width: 40%;
    fill: none;
    stroke: var(--color-text-muted);
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .title {
    display: block;
    margin: 10px 0 0;
    font-size: 15px;
    font-weight: 600;
    line-height: 1.3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .meta {
    display: block;
    font-size: 13px;
    color: var(--color-text-muted);
  }
</style>
