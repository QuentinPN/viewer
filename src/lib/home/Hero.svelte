<script lang="ts">
// Home hero (ADR 0015): one title at a time over its backdrop. Titles change only
// on request (arrows, dots, ← and →); no automatic rotation.
import { tick } from "svelte";
import type { HeroItem, MediaItem } from "../emby/types";
import { heroMeta, keptIndex, playLabel, playName, stepIndex } from "./hero";

interface Props {
  items: HeroItem[];
  /** Id being started by any card of the home page: buttons wait meanwhile. */
  starting: string | null;
  onPlay: (item: HeroItem) => void;
  onOpen: (item: MediaItem) => void;
}

let { items, starting, onPlay, onOpen }: Props = $props();

let shownId = $state<string | null>(null);
let shownIndex = $state(0);
let hero = $state<HTMLElement>();

const index = $derived(keptIndex(items, shownId, shownIndex));
const current = $derived(items[index]);
const label = $derived(current ? playLabel(current) : null);
const meta = $derived(current ? heroMeta(current) : "");

/** Shows title `i`; `refocus` moves the focus to that control of the new title. */
async function show(i: number, refocus: string | null = null) {
  shownIndex = i;
  shownId = items[i]?.detail.id ?? null;
  if (refocus) {
    await tick();
    hero?.querySelector<HTMLElement>(refocus)?.focus();
  }
}

function step(delta: number) {
  void show(stepIndex(index, delta, items.length));
}

function onkeydown(e: KeyboardEvent) {
  if (items.length < 2) return;
  if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
  e.preventDefault();
  // The title's buttons are rebuilt: the focus follows to the same control.
  const target = e.target as HTMLElement;
  const refocus = target.classList.contains("dot")
    ? '.dot[aria-current="true"]'
    : target.classList.contains("play")
      ? ".slide .play, .slide .info"
      : target.classList.contains("info")
        ? ".slide .info"
        : null;
  void show(
    stepIndex(index, e.key === "ArrowLeft" ? -1 : 1, items.length),
    refocus,
  );
}
</script>

{#if current}
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <section
    class="hero"
    aria-roledescription="carrousel"
    aria-label="À la une"
    bind:this={hero}
    {onkeydown}
  >
    <div class="slides" aria-live="polite">
      {#key current.detail.id}
        <div
          class="slide"
          role="group"
          aria-roledescription="diapositive"
          aria-label="{index + 1} sur {items.length}"
          data-item-id={current.detail.id}
        >
          <img
            class="backdrop"
            src={current.backdrop.src}
            srcset={current.backdrop.srcset}
            sizes="100vw"
            alt=""
            decoding="async"
          />
          <div class="scrim"></div>
          <div class="content">
            {#if current.reason === "resume"}
              <p class="eyebrow">Reprendre</p>
            {/if}
            <h2 class="title">
              {#if current.logo}
                <img
                  class="logo"
                  src={current.logo.src}
                  srcset={current.logo.srcset}
                  sizes="(max-width: 900px) 240px, 360px"
                  alt={current.detail.name}
                />
              {:else}
                {current.detail.name}
              {/if}
            </h2>
            {#if meta}<p class="meta">{meta}</p>{/if}
            {#if current.overview}<p class="overview">{current.overview}</p>{/if}
            <div class="actions">
              {#if label && current.playId}
                <button
                  type="button"
                  class="play"
                  aria-label={playName(current)}
                  aria-busy={starting === current.playId}
                  disabled={starting !== null}
                  onclick={() => onPlay(current)}
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13l10.5-6.5z" /></svg>
                  {starting === current.playId ? "Ouverture…" : label}
                </button>
              {/if}
              <button
                type="button"
                class="info"
                aria-label="Plus d'infos sur {current.detail.name}"
                onclick={() => onOpen(current.detail)}>Plus d'infos</button
              >
              {#if current.resume?.fraction != null}
                <div
                  class="progress"
                  role="progressbar"
                  aria-label="Déjà vu"
                  aria-valuemin="0"
                  aria-valuemax="100"
                  aria-valuenow={Math.round(current.resume.fraction * 100)}
                >
                  <span style:width="{current.resume.fraction * 100}%"></span>
                </div>
              {/if}
            </div>
          </div>
        </div>
      {/key}
    </div>

    {#if items.length > 1}
      <button
        type="button"
        class="arrow prev"
        aria-label="Titre précédent"
        onclick={() => step(-1)}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </button>
      <button
        type="button"
        class="arrow next"
        aria-label="Titre suivant"
        onclick={() => step(1)}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
      </button>
      <div class="dots">
        {#each items as item, i (item.detail.id)}
          <button
            type="button"
            class="dot"
            aria-label="Titre {i + 1} sur {items.length} : {item.detail.name}"
            aria-current={i === index ? "true" : undefined}
            onclick={() => show(i)}
          ></button>
        {/each}
      </div>
    {/if}
  </section>
{/if}

<style>
  .hero {
    position: relative;
    height: clamp(300px, 40vw, 540px);
    margin-bottom: 32px;
    border-radius: var(--radius-lg);
    overflow: hidden;
    background: var(--color-surface);
  }
  .slides,
  .slide {
    position: absolute;
    inset: 0;
  }
  .slide {
    animation: hero-in var(--motion-base) ease-out;
  }
  @keyframes hero-in {
    from {
      opacity: 0;
    }
  }
  .backdrop {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 25%;
  }
  .scrim {
    position: absolute;
    inset: 0;
    background:
      linear-gradient(90deg, rgb(11 13 17 / 0.88) 0%, rgb(11 13 17 / 0.55) 40%, transparent 72%),
      linear-gradient(0deg, rgb(11 13 17 / 0.8) 0%, transparent 45%);
  }
  .content {
    position: absolute;
    left: clamp(24px, 4vw, 56px);
    right: 30%;
    bottom: clamp(28px, 4vw, 52px);
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
  }
  .eyebrow {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--color-accent);
  }
  .title {
    margin: 0;
    font-size: clamp(26px, 3.4vw, 44px);
    line-height: 1.1;
    letter-spacing: -0.01em;
    text-shadow: 0 2px 12px rgb(0 0 0 / 0.5);
  }
  .logo {
    display: block;
    max-width: min(360px, 100%);
    max-height: clamp(64px, 9vw, 130px);
    object-fit: contain;
    object-position: left bottom;
  }
  .meta {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 15px;
  }
  .overview {
    margin: 0;
    max-width: 560px;
    color: var(--color-text);
    opacity: 0.9;
    line-height: 1.45;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-3);
    margin-top: var(--space-2);
  }
  .actions button {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    border: 0;
    border-radius: var(--radius-sm);
    padding: 10px 20px;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
    transition: background var(--motion-fast) ease-out;
  }
  .play {
    background: var(--color-text);
    color: var(--color-bg);
  }
  .play:hover:not(:disabled) {
    background: #ffffff;
  }
  .play:disabled {
    cursor: progress;
    opacity: 0.75;
  }
  .play svg {
    width: 18px;
    height: 18px;
    fill: currentColor;
  }
  .info {
    background: rgb(255 255 255 / 0.16);
    color: var(--color-text);
    backdrop-filter: blur(8px);
  }
  .info:hover {
    background: rgb(255 255 255 / 0.26);
  }
  .progress {
    width: 120px;
    height: 4px;
    border-radius: 2px;
    background: rgb(255 255 255 / 0.25);
    overflow: hidden;
  }
  .progress span {
    display: block;
    height: 100%;
    background: var(--color-accent);
  }
  .arrow {
    position: absolute;
    top: 50%;
    translate: 0 -50%;
    width: 44px;
    height: 44px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: rgb(11 13 17 / 0.45);
    color: var(--color-text);
    cursor: pointer;
    opacity: 0.7;
    transition:
      opacity var(--motion-fast) ease-out,
      background var(--motion-fast) ease-out;
  }
  .hero:hover .arrow,
  .arrow:focus-visible {
    opacity: 1;
  }
  .arrow:hover {
    background: rgb(11 13 17 / 0.7);
  }
  .arrow svg {
    width: 22px;
    height: 22px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.4;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .prev {
    left: var(--space-3);
  }
  .next {
    right: var(--space-3);
  }
  .dots {
    position: absolute;
    right: clamp(20px, 3vw, 40px);
    bottom: clamp(24px, 3vw, 40px);
    display: flex;
    gap: var(--space-1);
  }
  .dot {
    width: 24px;
    height: 24px;
    display: grid;
    place-items: center;
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
  }
  .dot::before {
    content: "";
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: rgb(255 255 255 / 0.4);
    transition:
      width var(--motion-fast) ease-out,
      background var(--motion-fast) ease-out;
  }
  .dot[aria-current="true"]::before {
    width: 20px;
    border-radius: 4px;
    background: var(--color-text);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  @media (max-width: 760px) {
    .content {
      right: var(--space-6);
    }
    .overview {
      -webkit-line-clamp: 2;
      line-clamp: 2;
    }
  }
</style>
