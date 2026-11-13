<script lang="ts">
// Titles of a person, collection, genre or studio, opened from a detail page
// over it (ADR 0121). The posters open their own detail page on top.
import { onMount, tick, untrack } from "svelte";
import { embyApi, errorHint, toEmbyError } from "../emby/api";
import type {
  EmbyError,
  ImageRef,
  MediaItem,
  TitlesSource,
} from "../emby/types";
import PosterCard from "../library/PosterCard.svelte";
import { initials, titlesCount } from "./pages";

interface Props {
  source: TitlesSource;
  title: string;
  subtitle: string;
  photo: ImageRef | null;
  /** Bumped when the connection comes back (ADR 0012). */
  refresh: number;
  onClose: () => void;
  onError: (error: EmbyError) => EmbyError;
  onOpen: (item: MediaItem) => void;
}

let {
  source,
  title,
  subtitle,
  photo,
  refresh,
  onClose,
  onError,
  onOpen,
}: Props = $props();

let titles = $state<MediaItem[] | null>(null);
let error = $state<EmbyError | null>(null);
let photoFailed = $state(false);
let heading = $state<HTMLElement | null>(null);
let section = $state<HTMLElement | null>(null);

async function load() {
  error = null;
  try {
    titles = await embyApi.titles(source);
  } catch (e) {
    error = onError(toEmbyError(e));
  }
}

let seenRefresh = untrack(() => refresh);
$effect(() => {
  if (refresh === seenRefresh) return;
  seenRefresh = refresh;
  untrack(() => void load());
});

/** Hidden under another page: keys belong to it then. */
function active() {
  return section?.checkVisibility() ?? false;
}

function onKeydown(e: KeyboardEvent) {
  if (e.defaultPrevented || !active()) return;
  if (e.key === "Escape" || (e.altKey && e.key === "ArrowLeft")) {
    e.preventDefault();
    onClose();
  }
}

function onMouseup(e: MouseEvent) {
  if (e.button === 3 && active()) {
    e.preventDefault();
    onClose();
  }
}

onMount(() => {
  void load();
  void tick().then(() => heading?.focus({ preventScroll: true }));
});
</script>

<svelte:window onkeydown={onKeydown} onmouseup={onMouseup} />

<section
  class="titles-page"
  aria-labelledby="titles-page-title"
  aria-busy={!titles && !error}
  bind:this={section}
>
  <header>
    <button
      type="button"
      class="back"
      aria-label="Retour"
      title="Retour (Échap)"
      onclick={onClose}
    >
      <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
    </button>
    {#if source.kind === "person"}
      <div class="photo" aria-hidden="true">
        {#if photo && !photoFailed}
          <img
            src={photo.src}
            srcset={photo.srcset}
            sizes="96px"
            alt=""
            decoding="async"
            onerror={() => (photoFailed = true)}
          />
        {:else}
          <span>{initials(title)}</span>
        {/if}
      </div>
    {/if}
    <div class="heading">
      <h1 id="titles-page-title" tabindex="-1" bind:this={heading}>{title}</h1>
      <p class="subtitle">
        {subtitle}{#if titles}{" · "}{titlesCount(titles.length)}{/if}
      </p>
    </div>
  </header>

  {#if error}
    <div class="state" role="alert">
      <p class="state-title">Impossible d'afficher ces titres</p>
      <p>{error.message}</p>
      {#if errorHint(error)}<p class="hint">{errorHint(error)}</p>{/if}
      <div class="actions">
        <button type="button" class="primary" onclick={load}>Réessayer</button>
        <button type="button" class="secondary" onclick={onClose}>Retour</button>
      </div>
    </div>
  {:else if titles && titles.length === 0}
    <div class="state">
      <p class="state-title">Aucun titre trouvé</p>
      <p>Rien de ta bibliothèque ne correspond pour l'instant.</p>
    </div>
  {:else}
    <ul class="grid" aria-label={title}>
      {#if titles}
        {#each titles as item (item.id)}
          <li><PosterCard {item} {onOpen} /></li>
        {/each}
      {:else}
        {#each { length: 12 } as _, i (i)}
          <li class="skeleton" aria-hidden="true"><div></div></li>
        {/each}
      {/if}
    </ul>
    {#if !titles}<p class="sr-only">Chargement des titres…</p>{/if}
  {/if}
</section>

<style>
  .titles-page {
    position: absolute;
    inset: 0;
    overflow-y: auto;
    --gutter: clamp(var(--space-4), 5vw, 80px);
    padding: var(--space-6) var(--gutter) 64px;
    background: var(--color-bg);
  }
  header {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    margin-bottom: 32px;
  }
  .back {
    flex: none;
    width: 40px;
    height: 40px;
    display: grid;
    place-items: center;
    border: none;
    border-radius: 50%;
    background: var(--fill-glass);
    color: var(--color-text);
    cursor: pointer;
    transition: background var(--motion-fast);
  }
  .back:hover {
    background: var(--fill-glass-hover);
  }
  .back svg {
    width: 18px;
    height: 18px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.8;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .photo {
    flex: none;
    width: 96px;
    height: 96px;
    border-radius: 50%;
    overflow: hidden;
    display: grid;
    place-items: center;
    background: var(--color-surface);
    color: var(--color-text-secondary);
    font-size: 28px;
    font-weight: 600;
  }
  .photo img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  h1 {
    margin: 0;
    font-size: clamp(26px, 3vw, 36px);
    outline: none;
  }
  h1:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 4px;
  }
  .subtitle {
    margin: var(--space-1) 0 0;
    color: var(--color-text-secondary);
  }
  .grid {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 24px 18px;
  }
  .skeleton div {
    aspect-ratio: 2 / 3;
    border-radius: var(--radius-md);
    background: var(--color-surface);
    animation: pulse 1.4s ease-in-out infinite;
  }
  @keyframes pulse {
    50% {
      opacity: 0.55;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .skeleton div {
      animation: none;
    }
  }
  .state {
    max-width: 560px;
  }
  .state-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--color-text);
  }
  .state p {
    color: var(--color-text-secondary);
  }
  .hint {
    font-size: 13px;
  }
  .actions {
    display: flex;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }
  .actions button {
    min-height: 40px;
    padding: 0 18px;
    border: none;
    border-radius: 999px;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .primary {
    background: var(--color-primary);
    color: var(--color-on-primary);
  }
  .secondary {
    background: var(--fill-glass);
    color: var(--color-text);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
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
