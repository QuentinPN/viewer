<script lang="ts">
// The card menu of the library screen (ADR 0100), as in the official Emby apps:
// a translucent panel next to the card, headed by its artwork, title and year,
// one row per action. "Ajouter à une playlist" swaps the rows for the user's
// playlists and a field naming a new one. A short notice confirms what was done.
import type { Attachment } from "svelte/attachments";
import { embyApi } from "../emby/api";
import type {
  EmbyError,
  PlaybackStart,
  PlaybackStopped,
  Playlist,
} from "../emby/types";
import {
  addedMessage,
  type MenuAction,
  type MenuIcon,
  menuActions,
  PLAYLIST_NAME_MAX,
  playlistName,
} from "./actions";
import type { CardMenuState, MenuRequest } from "./menuState.svelte";
import { EDGE, placeMenu } from "./position";

interface Props {
  menu: CardMenuState;
  onPlay: (start: PlaybackStart) => void;
  /** Reports the error upward (a 401 changes the screen) and returns it. */
  onError: (e: unknown) => EmbyError;
  onFavoriteChange: (itemId: string, favorite: boolean) => void;
}

let { menu, onPlay, onError, onFavoriteChange }: Props = $props();

const request = $derived(menu.request);
const actions = $derived(
  request ? menuActions(request.target, request.context) : [],
);

let panel = $state<HTMLElement | null>(null);
let field = $state<HTMLInputElement | null>(null);
let position = $state<{ left: number; top: number } | null>(null);
let view = $state<"actions" | "playlists">("actions");
let playlists = $state<Playlist[] | null>(null);
let listError = $state<EmbyError | null>(null);
let naming = $state(false);
let newName = $state("");
/** A playlist change in flight: the rows wait for it. */
let busy = $state(false);
/** Why the last playlist change failed; the menu stays open meanwhile. */
let panelError = $state<string | null>(null);
let notice = $state<{ text: string; error: boolean } | null>(null);
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
/** One playback negotiated at a time, as on the home rows. */
let starting = false;
let shown: MenuRequest | null = null;

// A new menu starts on its actions, the playlists read again.
$effect(() => {
  const current = request;
  if (current === shown) return;
  shown = current;
  view = "actions";
  playlists = null;
  listError = null;
  naming = false;
  panelError = null;
  position = null;
});

// Placed once its size is known, and again when it changes (playlists view).
$effect(() => {
  if (!panel || !request) return;
  const node = panel;
  const anchor = request.anchor;
  const titleBar =
    Number.parseFloat(
      getComputedStyle(document.documentElement).getPropertyValue(
        "--titlebar-height",
      ),
    ) || 0;
  const place = () => {
    const r = node.getBoundingClientRect();
    position = placeMenu(
      anchor,
      { width: r.width, height: r.height },
      { width: window.innerWidth, height: window.innerHeight },
      titleBar + EDGE,
    );
  };
  place();
  const observer = new ResizeObserver(place);
  observer.observe(node);
  return () => observer.disconnect();
});

// Focus goes to the first row of each view, once placed.
$effect(() => {
  if (!panel || !position) return;
  void view;
  const node = panel;
  queueMicrotask(() => {
    if (node.contains(document.activeElement)) return;
    node.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  });
});

$effect(() => {
  if (naming && field) {
    field.focus();
    field.select();
  }
});

$effect(() => {
  const unlisten = embyApi.onPlaybackStopped((state: PlaybackStopped) =>
    menu.noteWatched(state),
  );
  return () => {
    void unlisten.then((stop) => stop());
  };
});

$effect(() => () => clearTimeout(noticeTimer));

/** Under the window's other layers: fixed positions ignore the card's row. */
const portal: Attachment<HTMLElement> = (node) => {
  document.body.append(node);
  return () => node.remove();
};

/** Closes the menu; `refocus` gives the focus back to the card. */
function close(refocus: boolean) {
  const opener = request?.opener;
  menu.close();
  if (refocus && opener?.isConnected) opener.focus();
}

function announce(text: string, error = false) {
  clearTimeout(noticeTimer);
  notice = { text, error };
  noticeTimer = setTimeout(() => (notice = null), error ? 8000 : 4000);
}

function failed(what: string, e: unknown) {
  announce(`${what} : ${onError(e).message}`, true);
}

async function play(id: string, resume: boolean, title: string) {
  if (starting) return;
  starting = true;
  announce(`Lancement de « ${title} »…`);
  try {
    onPlay(await embyApi.play(id, resume));
    notice = null;
  } catch (e) {
    failed(`Impossible de lire « ${title} »`, e);
  } finally {
    starting = false;
  }
}

async function playNext(seriesId: string, title: string) {
  if (starting) return;
  try {
    const next = await embyApi.seriesNextEpisode(seriesId);
    if (next) await play(next.id, next.resume !== null, title);
    else announce(`Aucun épisode à lire dans « ${title} »`);
  } catch (e) {
    failed(`Impossible de lire « ${title} »`, e);
  }
}

async function setPlayed(id: string, played: boolean, title: string) {
  try {
    // The list views follow the playback-stopped event the command sends.
    await embyApi.setPlayed(id, played);
    announce(`« ${title} » marqué comme ${played ? "vu" : "non vu"}`);
  } catch (e) {
    failed(
      `« ${title} » n'a pas été marqué comme ${played ? "vu" : "non vu"}`,
      e,
    );
  }
}

async function setFavorite(id: string, favorite: boolean, title: string) {
  try {
    const state = await embyApi.setFavorite(id, favorite);
    onFavoriteChange(id, state);
    menu.noteFavorite(id, state);
    announce(
      state
        ? `« ${title} » ajouté aux favoris`
        : `« ${title} » retiré des favoris`,
    );
  } catch (e) {
    failed(
      favorite
        ? `« ${title} » n'a pas été ajouté aux favoris`
        : `« ${title} » n'a pas été retiré des favoris`,
      e,
    );
  }
}

async function hide(id: string, title: string) {
  try {
    await embyApi.hideFromResume(id);
    announce(`« ${title} » retiré de Reprendre`);
  } catch (e) {
    failed(`« ${title} » n'a pas été retiré de Reprendre`, e);
  }
}

function run(action: MenuAction) {
  const current = request;
  if (!current || busy) return;
  if (action.id === "playlist") {
    openPlaylists();
    return;
  }
  const { target: t } = current;
  // The card gets the focus back first: a card taken out of its row then
  // hands it to its neighbour (ADR 0096).
  close(true);
  switch (action.id) {
    case "play":
    case "resume":
      if (current.play) current.play();
      else void play(t.id, action.id === "resume", t.name);
      break;
    case "playNext":
      void playNext(t.id, t.name);
      break;
    case "played":
      if (current.markPlayed) current.markPlayed();
      else void setPlayed(t.id, true, t.name);
      break;
    case "unplayed":
      void setPlayed(t.id, false, t.name);
      break;
    case "favorite":
    case "unfavorite":
      void setFavorite(t.id, action.id === "favorite", t.name);
      break;
    case "removeResume":
      if (current.removeFromResume) current.removeFromResume();
      else void hide(t.id, t.name);
      break;
  }
}

function openPlaylists() {
  view = "playlists";
  naming = false;
  panelError = null;
  if (playlists === null) void loadPlaylists();
}

function back() {
  view = "actions";
  panelError = null;
  queueMicrotask(() =>
    panel?.querySelector<HTMLElement>('[data-action="playlist"]')?.focus(),
  );
}

async function loadPlaylists() {
  const current = request;
  listError = null;
  try {
    const list = await embyApi.playlists();
    if (request === current) playlists = list;
  } catch (e) {
    if (request === current) listError = onError(e);
  }
}

function startNaming() {
  if (!request) return;
  newName = request.target.name.slice(0, PLAYLIST_NAME_MAX);
  naming = true;
  panelError = null;
}

async function addTo(playlist: Playlist) {
  const current = request;
  if (!current || busy) return;
  busy = true;
  panelError = null;
  const title = current.target.name;
  try {
    const added = await embyApi.addToPlaylist(playlist.id, current.target.id);
    if (request === current) close(true);
    announce(addedMessage(title, playlist.name, added));
  } catch (e) {
    const error = onError(e);
    if (request === current) {
      panelError = `« ${title} » n'a pas été ajouté à « ${playlist.name} » : ${error.message}`;
    }
  } finally {
    busy = false;
  }
}

async function create(e: SubmitEvent) {
  e.preventDefault();
  const current = request;
  if (!current || busy) return;
  const name = playlistName(newName);
  if (!name) {
    panelError = `Donne à la playlist un nom de ${PLAYLIST_NAME_MAX} caractères au plus, sans retour à la ligne.`;
    field?.focus();
    return;
  }
  busy = true;
  panelError = null;
  const title = current.target.name;
  try {
    const created = await embyApi.createPlaylist(name, current.target.id);
    if (request === current) close(true);
    announce(`Playlist « ${created.name} » créée avec « ${title} »`);
  } catch (e) {
    const error = onError(e);
    // The name typed stays in the field, to try again.
    if (request === current) {
      panelError = `La playlist « ${name} » n'a pas été créée : ${error.message}`;
    }
  } finally {
    busy = false;
  }
}

/** Arrows move between the rows, Escape closes, Tab leaves the menu. */
function onkeydown(e: KeyboardEvent) {
  if (!panel) return;
  if (e.key === "Escape") {
    e.preventDefault();
    close(true);
    return;
  }
  const inField = e.target instanceof HTMLInputElement;
  const onRow =
    e.target instanceof HTMLElement &&
    e.target.getAttribute("role") === "menuitem";
  if (e.key === "Tab" && onRow) {
    e.preventDefault();
    close(true);
    return;
  }
  if (inField) return;
  if (e.key === "ArrowLeft" && view === "playlists") {
    e.preventDefault();
    back();
    return;
  }
  if (
    e.key === "ArrowRight" &&
    e.target instanceof HTMLElement &&
    e.target.dataset.action === "playlist"
  ) {
    e.preventDefault();
    openPlaylists();
    return;
  }
  const rows = [...panel.querySelectorAll<HTMLElement>('[role="menuitem"]')];
  if (rows.length === 0) return;
  const at = rows.indexOf(document.activeElement as HTMLElement);
  let next: HTMLElement | undefined;
  if (e.key === "ArrowDown") next = rows[(at + 1) % rows.length];
  else if (e.key === "ArrowUp")
    next = rows[(at - 1 + rows.length) % rows.length];
  else if (e.key === "Home") next = rows[0];
  else if (e.key === "End") next = rows[rows.length - 1];
  if (next) {
    e.preventDefault();
    next.focus();
  }
}

/** A press anywhere else closes the menu, without taking the focus. */
function onpointerdown(e: PointerEvent) {
  if (request && panel && !panel.contains(e.target as Node)) close(false);
}

function onscroll(e: Event) {
  if (request && panel && !panel.contains(e.target as Node)) close(false);
}
</script>

<svelte:window onresize={() => request && close(false)} />
<svelte:document onpointerdowncapture={onpointerdown} onscrollcapture={onscroll} />

{#snippet icon(name: MenuIcon)}
  <svg viewBox="0 0 16 16" aria-hidden="true" class:filled={name === "play" || name === "heartOff"}>
    {#if name === "play"}
      <path d="M5 3.2v9.6l7.6-4.8z" />
    {:else if name === "playlist"}
      <path d="M2.5 4h8M2.5 8h8M2.5 12h4.5M12.5 9.5v5M10 12h5" />
    {:else if name === "check"}
      <path d="M3 8.5 6.5 12 13 4.5" />
    {:else if name === "uncheck"}
      <path d="M4 6.5h6a3 3 0 0 1 0 6H6.5M6.5 4 4 6.5 6.5 9" />
    {:else if name === "heart" || name === "heartOff"}
      <path
        d="M8 13.4S2.8 10.2 2.8 6.6A2.6 2.6 0 0 1 8 5.3a2.6 2.6 0 0 1 5.2 1.3c0 3.6-5.2 6.8-5.2 6.8z"
      />
    {:else}
      <path d="M4.5 4.5l7 7m0-7-7 7" />
    {/if}
  </svg>
{/snippet}

<div class="card-menu-root" {@attach portal}>
  {#if request}
    {@const t = request.target}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div
      bind:this={panel}
      class="panel"
      class:placed={position !== null}
      style:left="{position?.left ?? 0}px"
      style:top="{position?.top ?? 0}px"
      {onkeydown}
      oncontextmenu={(e) => e.preventDefault()}
    >
      <div class="head" aria-hidden="true">
        <div class="thumb" class:wide={t.wide}>
          {#if t.image}
            <img src={t.image.src} srcset={t.image.srcset} sizes="80px" alt="" />
          {/if}
        </div>
        <div class="names">
          <p class="name">{t.name}</p>
          {#if t.subtitle}<p class="sub">{t.subtitle}</p>{/if}
        </div>
      </div>

      {#if view === "actions"}
        <div class="rows" role="menu" aria-label="Actions pour « {t.name} »">
          {#each actions as action (action.id)}
            <button
              type="button"
              role="menuitem"
              tabindex="-1"
              class="row"
              data-action={action.id}
              aria-haspopup={action.id === "playlist" ? "menu" : undefined}
              onclick={() => run(action)}
            >
              <span class="label">{action.label}</span>
              {@render icon(action.icon)}
            </button>
          {/each}
        </div>
      {:else}
        <div
          class="rows"
          role="menu"
          aria-label="Ajouter « {t.name} » à une playlist"
          aria-busy={busy || (playlists === null && !listError)}
        >
          <button type="button" role="menuitem" tabindex="-1" class="row back" onclick={back}>
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3 5 8l5 5" /></svg>
            <span class="label">Ajouter à une playlist</span>
          </button>
          <div class="separator" role="separator"></div>
          {#if naming}
            <form class="new" onsubmit={create}>
              <input
                bind:this={field}
                bind:value={newName}
                type="text"
                maxlength={PLAYLIST_NAME_MAX}
                aria-label="Nom de la nouvelle playlist"
                disabled={busy}
              />
              <button type="submit" class="create" disabled={busy}>Créer</button>
            </form>
          {:else}
            <button
              type="button"
              role="menuitem"
              tabindex="-1"
              class="row"
              aria-disabled={busy}
              onclick={startNaming}
            >
              <span class="label">Nouvelle playlist…</span>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" /></svg>
            </button>
          {/if}
          {#if panelError}
            <p class="problem" role="alert">{panelError}</p>
          {/if}
          <div class="list">
            {#if listError}
              <p class="quiet">Playlists indisponibles : {listError.message}</p>
              <button
                type="button"
                role="menuitem"
                tabindex="-1"
                class="row"
                onclick={loadPlaylists}
              >
                <span class="label">Réessayer</span>
              </button>
            {:else if playlists === null}
              <p class="quiet">Chargement des playlists…</p>
            {:else if playlists.length === 0}
              <p class="quiet">Aucune playlist pour l'instant</p>
            {:else}
              {#each playlists as playlist (playlist.id)}
                <button
                  type="button"
                  role="menuitem"
                  tabindex="-1"
                  class="row"
                  aria-disabled={busy}
                  onclick={() => addTo(playlist)}
                >
                  <span class="label">{playlist.name}</span>
                </button>
              {/each}
            {/if}
          </div>
        </div>
      {/if}
    </div>
  {/if}

  {#if notice}
    <p class="notice" class:error={notice.error} role={notice.error ? "alert" : "status"}>
      {notice.text}
    </p>
  {/if}
</div>

<style>
  .panel {
    position: fixed;
    z-index: 50;
    width: 300px;
    max-width: calc(100vw - 2 * var(--space-2));
    max-height: calc(100vh - var(--titlebar-height) - 2 * var(--space-2));
    overflow-y: auto;
    padding: var(--space-2);
    border-radius: var(--radius-md);
    background: var(--material-thick);
    backdrop-filter: var(--material-blur);
    border: 1px solid rgb(255 255 255 / 10%);
    box-shadow: 0 24px 60px rgb(0 0 0 / 55%);
    color: var(--color-text);
    visibility: hidden;
    opacity: 0;
    transform: scale(0.97);
    transform-origin: top left;
    transition:
      opacity var(--motion-fast) ease-out,
      transform var(--motion-fast) ease-out;
  }
  .panel.placed {
    visibility: visible;
    opacity: 1;
    transform: none;
  }
  .head {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-1) var(--space-2) var(--space-3);
  }
  .thumb {
    flex: none;
    width: 40px;
    aspect-ratio: 2 / 3;
    border-radius: 6px;
    overflow: hidden;
    background: linear-gradient(160deg, var(--color-elevated), var(--color-surface));
  }
  .thumb.wide {
    width: 72px;
    aspect-ratio: 16 / 9;
  }
  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .names {
    min-width: 0;
  }
  .name,
  .sub {
    margin: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .name {
    font-weight: 600;
  }
  .sub {
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .rows {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding-top: var(--space-2);
    border-top: 1px solid var(--separator);
  }
  .row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    width: 100%;
    min-height: 40px;
    padding: var(--space-2) var(--space-3);
    border: 0;
    border-radius: var(--radius-sm);
    background: transparent;
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .row .label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .row:hover,
  .row:focus-visible {
    background: var(--fill-glass-hover);
    outline: none;
  }
  .row:focus-visible {
    box-shadow: inset 0 0 0 2px var(--color-accent);
  }
  .row[aria-disabled="true"] {
    cursor: progress;
    opacity: 0.6;
  }
  .row.back .label {
    font-weight: 600;
  }
  svg {
    flex: none;
    width: 16px;
    height: 16px;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.6;
    stroke-linecap: round;
    stroke-linejoin: round;
    color: var(--color-text-secondary);
  }
  svg.filled {
    fill: currentColor;
  }
  .separator {
    height: 1px;
    margin: var(--space-1) var(--space-2);
    background: var(--separator);
  }
  .new {
    display: flex;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-1) var(--space-2);
  }
  .new input {
    flex: 1;
    min-width: 0;
    height: 36px;
    padding: 0 var(--space-3);
    border: 1px solid rgb(255 255 255 / 14%);
    border-radius: var(--radius-sm);
    background: rgb(0 0 0 / 30%);
    color: inherit;
    font: inherit;
  }
  .new input:focus-visible,
  .create:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 1px;
  }
  .create {
    flex: none;
    height: 36px;
    padding: 0 var(--space-4);
    border: 0;
    border-radius: var(--radius-pill);
    background: var(--color-primary);
    color: var(--color-on-primary);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .create:disabled,
  .new input:disabled {
    cursor: progress;
    opacity: 0.6;
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: 2px;
    max-height: 40vh;
    overflow-y: auto;
  }
  .quiet,
  .problem {
    margin: 0;
    padding: var(--space-2) var(--space-3);
    font-size: 13px;
    color: var(--color-text-secondary);
  }
  .problem {
    color: var(--color-text);
    border-left: 3px solid var(--color-danger);
    margin: var(--space-1) var(--space-2);
  }
  .notice {
    position: fixed;
    left: 50%;
    bottom: var(--space-6);
    transform: translateX(-50%);
    z-index: 50;
    width: max-content;
    max-width: min(560px, calc(100% - 2 * var(--space-4)));
    margin: 0;
    padding: var(--space-3) 20px;
    background: rgb(36 36 42 / 72%);
    backdrop-filter: blur(40px) saturate(180%);
    border: 1px solid rgb(255 255 255 / 10%);
    border-radius: 20px;
    box-shadow: 0 24px 60px rgb(0 0 0 / 55%);
    color: var(--color-text);
  }
  .notice.error {
    border-left: 3px solid var(--color-danger);
  }
  @media (prefers-reduced-motion: reduce) {
    .panel {
      transform: none;
    }
  }
</style>
