<script lang="ts">
// A row of posters under a detail page (ADR 0121): similar titles, or the
// titles of a collection. Stays hidden while empty; a failure says so quietly.
import { onMount, untrack } from "svelte";
import { embyApi, toEmbyError } from "../emby/api";
import type { EmbyError, MediaItem, TitlesSource } from "../emby/types";
import PosterCard from "../library/PosterCard.svelte";

interface Props {
  source: TitlesSource;
  heading: string;
  /** Unique within the page, for the heading id. */
  rowId: string;
  /** Bumped when the connection comes back (ADR 0012). */
  refresh: number;
  onError: (error: EmbyError) => EmbyError;
  onOpen: (item: MediaItem) => void;
  /** Opens the whole list as a page; no link without it. */
  onMore?: (() => void) | undefined;
  /** Titles not to show, e.g. the page's own title in its collection. */
  exclude?: string | undefined;
}

let {
  source,
  heading,
  rowId,
  refresh,
  onError,
  onOpen,
  onMore,
  exclude,
}: Props = $props();

let titles = $state<MediaItem[] | null>(null);
let failed = $state(false);
const shown = $derived(titles?.filter((t) => t.id !== exclude) ?? null);

async function load() {
  failed = false;
  try {
    titles = await embyApi.titles(source);
  } catch (e) {
    onError(toEmbyError(e));
    failed = true;
  }
}

let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(() => void load());
});

onMount(() => void load());
</script>

{#if failed || (shown && shown.length > 0)}
  <section class="title-row" aria-labelledby={rowId}>
    <div class="head">
      <h2 id={rowId}>{heading}</h2>
      {#if onMore && !failed}
        <button type="button" class="more" onclick={onMore}>Tout voir</button>
      {/if}
    </div>
    {#if failed}
      <p class="failed">
        Impossible d'afficher ces titres.
        <button type="button" class="more" onclick={load}>Réessayer</button>
      </p>
    {:else if shown}
      <ul aria-label={heading}>
        {#each shown as item (item.id)}
          <li><PosterCard {item} {onOpen} /></li>
        {/each}
      </ul>
    {/if}
  </section>
{/if}

<style>
  .title-row {
    margin-top: 40px;
  }
  .head {
    display: flex;
    align-items: baseline;
    gap: var(--space-4);
    margin-bottom: var(--space-3);
  }
  h2 {
    margin: 0;
    font-size: 20px;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 4px 4px 12px;
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 150px;
    gap: 18px;
    overflow-x: auto;
    scroll-snap-type: x proximity;
  }
  li {
    scroll-snap-align: start;
  }
  .more {
    padding: 0;
    border: none;
    background: none;
    color: var(--color-text-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
    text-decoration: underline;
    text-underline-offset: 3px;
  }
  .more:hover {
    color: var(--color-text);
  }
  .more:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .failed {
    color: var(--color-text-secondary);
  }
</style>
