<script lang="ts">
// A title of Request as a library poster card (lift, focus), its status at the
// bottom left of the artwork.
import PosterCard from "../library/PosterCard.svelte";
import "./requests.css";
import { cardItem } from "./browse";
import { mediaStatusText, statusTone } from "./labels";
import type { MediaSummary } from "./types";

interface Props {
  media: MediaSummary;
  onOpen: (media: MediaSummary) => void;
}

let { media, onOpen }: Props = $props();

const item = $derived(cardItem(media));
</script>

<PosterCard {item} onOpen={() => onOpen(media)}>
  {#snippet badge()}
    {#if media.status}
      <span class="ur-badge ur-tone-{statusTone(media.status)}">
        {mediaStatusText(media.status, media.progress)}
      </span>
    {/if}
  {/snippet}
</PosterCard>
