<script lang="ts">
// Small poster of a list row; an empty frame when there is none or it fails.
import type { ImageRef } from "../emby/types";

interface Props {
  image: ImageRef | null | undefined;
  /** Rendered width in px. */
  width?: number;
}

let { image, width = 48 }: Props = $props();

let failed = $state(false);
</script>

<span class="thumb" style="width: {width}px">
  {#if image && !failed}
    <img
      src={image.src}
      srcset={image.srcset}
      sizes="{width}px"
      alt=""
      loading="lazy"
      decoding="async"
      onerror={() => (failed = true)}
    />
  {/if}
</span>

<style>
  .thumb {
    flex: 0 0 auto;
    aspect-ratio: 2 / 3;
    border-radius: 6px;
    overflow: hidden;
    background: var(--color-elevated);
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
</style>
