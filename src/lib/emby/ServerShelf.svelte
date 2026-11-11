<script lang="ts">
// Pre-connection shelf « L'étagère » (ADR 0103): one tile per saved server and
// user, newest first. A tile with a saved token validates it (restore flow); a
// rejected or signed-out tile asks the password at once. « Gérer » forgets a
// server on this device only. Secondary views open over the shelf.
import { onMount, tick } from "svelte";
import {
  columnsOf,
  hostOf,
  initialOf,
  moveIndex,
  passwordNotice,
  tileAction,
  tileHue,
  tileKey,
  tileLabel,
} from "../connection/shelf";
import Logo from "../window/Logo.svelte";
import { embyApi, errorHint, toEmbyError } from "./api";
import SignIn from "./SignIn.svelte";
import type { EmbyError, ShelfServer, SignedInUser } from "./types";

interface Props {
  /** Why the shelf is shown, e.g. a session closed on the server. */
  notice?: EmbyError | null;
  onSignedIn: (user: SignedInUser) => void;
  /** A tile with a saved token: validated by the restore flow. */
  onResumeSaved: (serverId: string, userId: string) => void;
}

let { notice = null, onSignedIn, onResumeSaved }: Props = $props();

type View =
  | { kind: "shelf" }
  | { kind: "manual" }
  | { kind: "password"; tile: ShelfServer }
  | { kind: "confirm"; tile: ShelfServer };

let tiles = $state<ShelfServer[] | null>(null);
let loadError = $state<EmbyError | null>(null);
let view = $state<View>({ kind: "shelf" });
let manage = $state(false);
let forgetting = $state(false);
let forgetError = $state<EmbyError | null>(null);
let tileButtons = $state<HTMLButtonElement[]>([]);
let manualButton = $state<HTMLButtonElement | null>(null);
let manageButton = $state<HTMLButtonElement | null>(null);
let doneButton = $state<HTMLButtonElement | null>(null);
let cancelButton = $state<HTMLButtonElement | null>(null);
/** Tile to focus again when a secondary view closes. */
let lastIndex = 0;

const titleId = $derived(
  view.kind === "manual" || view.kind === "password"
    ? "shelf-title"
    : "signin-title",
);
const empty = $derived(tiles !== null && tiles.length === 0);
const subtitle = $derived(
  manage
    ? "Choisissez le serveur à oublier."
    : empty
      ? "Bienvenue. Ajoutez votre premier serveur Emby."
      : "Qui regarde ? Choisissez un serveur.",
);

onMount(() => {
  void load(true);
});

async function load(focusFirst: boolean) {
  loadError = null;
  try {
    tiles = await embyApi.shelf();
  } catch (e) {
    loadError = toEmbyError(e);
    tiles = [];
  }
  if (focusFirst) {
    await tick();
    focusTile(0);
  }
}

function focusTile(index: number) {
  const count = tiles?.length ?? 0;
  if (count === 0) {
    manualButton?.focus();
    return;
  }
  tileButtons[Math.min(Math.max(index, 0), count - 1)]?.focus();
}

function choose(tile: ShelfServer, index: number) {
  lastIndex = index;
  if (manage) {
    forgetError = null;
    view = { kind: "confirm", tile };
    void tick().then(() => cancelButton?.focus());
    return;
  }
  if (tileAction(tile) === "resume") {
    onResumeSaved(tile.server.id, tile.server.userId);
    return;
  }
  view = { kind: "password", tile };
}

async function closeView() {
  const wasManual = view.kind === "manual";
  view = { kind: "shelf" };
  forgetError = null;
  await tick();
  if (wasManual) manualButton?.focus();
  else focusTile(lastIndex);
}

function openManual() {
  view = { kind: "manual" };
}

async function toggleManage() {
  manage = !manage;
  await tick();
  if (manage) doneButton?.focus();
  else manageButton?.focus();
}

async function forget(tile: ShelfServer) {
  forgetting = true;
  forgetError = null;
  try {
    await embyApi.forgetServer(tile.server.id, tile.server.userId);
  } catch (e) {
    forgetError = toEmbyError(e);
    forgetting = false;
    return;
  }
  forgetting = false;
  view = { kind: "shelf" };
  await load(false);
  if (tiles?.length === 0) manage = false;
  await tick();
  if (manage) focusTile(lastIndex);
  else manualButton?.focus();
}

function onTilesKeydown(event: KeyboardEvent) {
  const count = tiles?.length ?? 0;
  const index = tileButtons.indexOf(
    document.activeElement as HTMLButtonElement,
  );
  if (index < 0) return;
  const columns = columnsOf(
    tileButtons.slice(0, count).map((b) => b.offsetTop),
  );
  const next = moveIndex(index, event.key, count, columns);
  if (next === null) return;
  event.preventDefault();
  focusTile(next);
}

function onShelfKeydown(event: KeyboardEvent) {
  if (event.key === "Escape" && manage && view.kind === "shelf") {
    event.preventDefault();
    void toggleManage();
  }
}

function onConfirmKeydown(event: KeyboardEvent) {
  if (event.key !== "Escape" || forgetting) return;
  event.preventDefault();
  event.stopPropagation();
  void closeView();
}
</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<main class="screen" onkeydown={onShelfKeydown}>
  <!-- The open sign-in card owns #signin-title (E2E hook): one id at a time. -->
  <section class="shelf" aria-labelledby={titleId} inert={view.kind !== "shelf"}>
    <div class="brand">
      <Logo size={96} />
      <h1 id={titleId}>Viewer</h1>
      <p class="subtitle">{subtitle}</p>
    </div>

    {#if notice || loadError}
      {@const shown = (loadError ?? notice) as EmbyError}
      <div class="notice" role="alert">
        <p>{shown.message}</p>
        {#if errorHint(shown)}<p class="hint">{errorHint(shown)}</p>{/if}
        {#if loadError}
          <button type="button" class="link" onclick={() => void load(true)}>Réessayer</button>
        {/if}
      </div>
    {/if}

    {#if tiles !== null}
      <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
      <ul class="tiles" aria-label="Serveurs enregistrés" onkeydown={onTilesKeydown}>
        {#each tiles as tile, index (tileKey(tile))}
          <li>
            <button
              type="button"
              class="tile"
              class:manage
              style:--h={tileHue(tile.server.id)}
              aria-label={tileLabel(tile, manage)}
              data-server-id={tile.server.id}
              data-user-id={tile.server.userId}
              data-access={tile.access}
              bind:this={tileButtons[index]}
              onclick={() => choose(tile, index)}
            >
              <span class="art" aria-hidden="true">
                <span class="initial">{initialOf(tile.server.name)}</span>
                {#if tile.access === "rejected" && !manage}
                  <span class="pill">Mot de passe requis</span>
                {/if}
                {#if manage}<span class="forget">Oublier</span>{/if}
              </span>
              <span class="meta" aria-hidden="true">
                <span class="n">{tile.server.name}</span>
                <span class="a">{tile.server.userName} · {hostOf(tile.server.url)}</span>
              </span>
            </button>
          </li>
        {/each}
        {#if empty}
          <li class="tile ghost" aria-hidden="true">
            <span class="art">Aucun serveur pour l'instant</span>
          </li>
        {/if}
      </ul>

      <div class="bar">
        {#if manage}
          <button type="button" class="primary" bind:this={doneButton} onclick={toggleManage}>
            Terminer
          </button>
        {:else}
          <button
            type="button"
            class:primary={empty}
            bind:this={manualButton}
            onclick={openManual}
          >
            Se connecter manuellement
          </button>
          {#if !empty}
            <button type="button" bind:this={manageButton} onclick={toggleManage}>Gérer</button>
          {/if}
        {/if}
      </div>
    {/if}
  </section>

  {#if view.kind === "manual"}
    <div class="scrim">
      <SignIn {onSignedIn} onBack={() => void closeView()} />
    </div>
  {:else if view.kind === "password"}
    {#key tileKey(view.tile)}
      <div class="scrim">
        <SignIn
          reauth={view.tile.server}
          notice={passwordNotice(view.tile)}
          {onSignedIn}
          onBack={() => void closeView()}
        />
      </div>
    {/key}
  {:else if view.kind === "confirm"}
    {@const name = view.tile.server.name}
    {@const tile = view.tile}
    <div class="scrim dialog-scrim">
      <div
        class="dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="forget-title"
        aria-describedby="forget-text"
        tabindex="-1"
        onkeydown={onConfirmKeydown}
      >
        <h2 id="forget-title">Oublier « {name} » ?</h2>
        <p id="forget-text">
          L'adresse, le nom d'utilisateur et la session enregistrés sur cet appareil seront
          effacés. Rien n'est supprimé sur le serveur.
        </p>
        {#if forgetError}
          <div class="notice" role="alert">
            <p>{forgetError.message}</p>
            {#if errorHint(forgetError)}<p class="hint">{errorHint(forgetError)}</p>{/if}
          </div>
        {/if}
        <div class="pair">
          <button
            type="button"
            bind:this={cancelButton}
            disabled={forgetting}
            onclick={() => void closeView()}
          >
            Annuler
          </button>
          <button
            type="button"
            class="danger"
            disabled={forgetting}
            onclick={() => void forget(tile)}
          >
            {forgetting ? "Suppression…" : "Oublier ce serveur"}
          </button>
        </div>
      </div>
    </div>
  {/if}
</main>

<style>
  .screen {
    position: relative;
    box-sizing: border-box;
    height: 100%;
    overflow-y: auto;
    display: grid;
    place-items: safe center;
    padding: var(--space-8, 40px) var(--space-4);
    background:
      radial-gradient(60% 60% at 50% 45%, rgb(42 50 66 / 55%) 0%, rgb(0 0 0 / 0%) 100%),
      var(--color-bg);
  }
  .shelf {
    display: grid;
    justify-items: center;
    gap: 34px;
    width: 100%;
  }
  .brand {
    display: grid;
    justify-items: center;
    gap: var(--space-2);
    text-align: center;
  }
  h1 {
    margin: var(--space-2) 0 0;
    font-size: 34px;
    font-weight: 700;
    letter-spacing: -0.02em;
  }
  .subtitle {
    margin: 0;
    font-size: 16px;
    color: var(--color-text-secondary);
  }
  .notice {
    box-sizing: border-box;
    width: min(440px, 100%);
    border-radius: var(--radius-sm);
    padding: var(--space-3) var(--space-4);
    background: rgb(255 105 97 / 14%);
    border: 1px solid rgb(255 105 97 / 30%);
  }
  .notice p {
    margin: 0;
    color: var(--color-danger);
  }
  /* Rust messages start lowercase so they can be embedded in sentences. */
  .notice p:first-child::first-letter {
    text-transform: uppercase;
  }
  .notice .hint {
    margin-top: var(--space-1);
    color: var(--color-text-secondary);
    font-size: 13px;
  }
  .tiles {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 22px;
    max-width: min(100%, 1060px);
  }
  .tile {
    --h: 210;
    box-sizing: border-box;
    width: 230px;
    display: grid;
    gap: 10px;
    padding: 0;
    border: 0;
    border-radius: 16px;
    background: none;
    color: var(--color-text);
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .art {
    position: relative;
    display: grid;
    place-items: center;
    aspect-ratio: 16 / 10;
    border-radius: 16px;
    border: 1px solid rgb(255 255 255 / 10%);
    background: linear-gradient(150deg, hsl(var(--h) 35% 30%), hsl(var(--h) 30% 12%));
    color: hsl(var(--h) 60% 90%);
    font-family: var(--font-display);
    font-size: 56px;
    font-weight: 700;
    transition:
      transform var(--motion-lift) var(--ease-spring),
      box-shadow var(--motion-lift) var(--ease-spring);
  }
  .tile:hover .art {
    filter: brightness(1.12);
  }
  .tile:focus-visible {
    outline: none;
  }
  .tile:focus-visible .art {
    outline: 3px solid var(--color-accent);
    outline-offset: 3px;
    transform: scale(var(--lift-scale));
    box-shadow: var(--shadow-lift);
  }
  .pill {
    position: absolute;
    left: 10px;
    bottom: 10px;
    padding: 3px 10px;
    border-radius: 999px;
    background: rgb(0 0 0 / 55%);
    color: #ffd18a;
    font-family: inherit;
    font-size: 12px;
    font-weight: 600;
  }
  .forget {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    border-radius: inherit;
    background: rgb(255 105 97 / 78%);
    color: #000;
    font-size: 16px;
    font-weight: 700;
  }
  .meta {
    display: grid;
    padding: 0 4px;
    min-width: 0;
  }
  .n,
  .a {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  .n {
    font-size: 16px;
    font-weight: 600;
  }
  .a {
    font-size: 12.5px;
    color: var(--color-text-muted);
  }
  .tile.ghost {
    cursor: default;
  }
  .ghost .art {
    box-sizing: border-box;
    padding: 16px;
    background: none;
    border: 1px dashed rgb(255 255 255 / 20%);
    color: var(--color-text-muted);
    font-family: inherit;
    font-size: 15px;
    font-weight: 400;
    text-align: center;
  }
  .bar,
  .pair {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: var(--space-3);
  }
  button:not(.tile) {
    height: 46px;
    padding: 0 var(--space-6);
    border: 0;
    border-radius: var(--radius-md);
    background: var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-size: 16px;
    font-weight: 600;
    cursor: pointer;
    transition:
      background var(--motion-fast),
      transform var(--motion-lift) var(--ease-spring);
  }
  button:not(.tile):hover:not(:disabled) {
    background: var(--fill-glass-hover);
  }
  button.primary {
    background: var(--color-primary);
    color: var(--color-on-primary);
  }
  button.primary:hover:not(:disabled) {
    background: #fff;
  }
  button.danger {
    background: var(--color-danger);
    color: #000;
  }
  button.danger:hover:not(:disabled) {
    background: #ff8a84;
  }
  button.link {
    height: auto;
    margin-top: var(--space-2);
    padding: var(--space-1) var(--space-2);
    background: transparent;
    color: var(--color-accent);
    font-size: 15px;
  }
  button:not(.tile):focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  button:disabled {
    opacity: 0.55;
    cursor: default;
  }
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 10;
  }
  .scrim :global(.screen) {
    padding-top: calc(var(--titlebar-height) + var(--space-4));
    background: rgb(0 0 0 / 60%);
    backdrop-filter: blur(6px);
  }
  .dialog-scrim {
    display: grid;
    place-items: center;
    padding: var(--space-4);
    background: rgb(0 0 0 / 60%);
    backdrop-filter: blur(6px);
  }
  .dialog {
    box-sizing: border-box;
    width: min(440px, 100%);
    display: grid;
    gap: var(--space-4);
    padding: 32px;
    border-radius: 28px;
    background: rgb(36 36 42 / 92%);
    border: 1px solid rgb(255 255 255 / 10%);
    box-shadow: 0 40px 80px rgb(0 0 0 / 60%);
    text-align: center;
  }
  .dialog:focus {
    outline: none;
  }
  .dialog h2 {
    margin: 0;
    font-size: 22px;
    font-weight: 700;
    overflow-wrap: anywhere;
  }
  .dialog > p {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 15px;
    line-height: 1.5;
  }
  .dialog .notice {
    text-align: left;
  }
  .pair > button {
    flex: 1 1 0;
  }
  @media (max-height: 760px) {
    .shelf {
      gap: var(--space-6);
    }
    .brand :global(svg) {
      width: 64px;
      height: 64px;
    }
    h1 {
      font-size: 28px;
    }
  }
  @media (max-width: 560px) {
    .tile {
      width: 160px;
    }
    .art {
      font-size: 40px;
    }
  }
</style>
