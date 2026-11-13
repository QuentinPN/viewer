<script lang="ts">
import { onMount } from "svelte";
import { embyApi, toEmbyError } from "../emby/api";
import type { EmbyError, ServerSummary } from "../emby/types";
import { playerApi } from "../player/api";
import {
  aboutRows,
  decoderRows,
  hdrRows,
  type SystemReport,
  systemApi,
} from "./about";

let version = $state<string | null>(null);
let report = $state<SystemReport | null>(null);
let reportFailed = $state(false);
// `undefined` while asking the server.
let server = $state<ServerSummary | null | undefined>(undefined);
let serverError = $state<EmbyError | null>(null);

const rows = $derived(aboutRows(version, report));

async function loadServer() {
  server = undefined;
  serverError = null;
  try {
    server = await embyApi.serverInfo();
  } catch (e) {
    server = null;
    serverError = toEmbyError(e);
  }
}

onMount(() => {
  playerApi.appInfo().then(
    (info) => (version = info.version),
    () => {},
  );
  systemApi.report().then(
    (r) => (report = r),
    () => (reportFailed = true),
  );
  void loadServer();
});
</script>

<section class="panel" aria-labelledby="settings-server">
  <h2 id="settings-server">Serveur</h2>
  {#if server === undefined}
    <p class="muted" aria-busy="true">Connexion au serveur…</p>
  {:else if server}
    <dl>
      <dt>Nom</dt>
      <dd>{server.name}</dd>
      <dt>Version d'Emby</dt>
      <dd>{server.version}</dd>
      <dt>Adresse</dt>
      <dd>{server.url}{server.encrypted ? "" : " (non chiffrée)"}</dd>
    </dl>
  {:else}
    <div class="problem" role="alert">
      <p>Serveur injoignable{serverError ? ` : ${serverError.message}` : ""}</p>
      <button type="button" class="secondary" onclick={loadServer}>Réessayer</button>
    </div>
  {/if}
</section>

<section class="panel" aria-labelledby="settings-capabilities">
  <h2 id="settings-capabilities">Capacités de lecture</h2>
  <p class="lead">
    Ce que la carte graphique principale décode elle-même. En « Logiciel », la vidéo
    se lit quand même, mais c'est le processeur qui travaille.
  </p>
  {#if report === null && !reportFailed}
    <p class="muted" aria-busy="true">Lecture des capacités…</p>
  {:else}
    <dl>
      {#each decoderRows(report?.decoders ?? null) as row (row.label)}
        <dt>{row.label}</dt>
        <dd>{row.value}</dd>
      {/each}
      {#each hdrRows(report?.displays ?? []) as row (row.label)}
        <dt>{row.label}</dt>
        <dd>{row.value}</dd>
      {/each}
      <dt>Dolby Vision</dt>
      <dd>
        Pas de lecture certifiée : selon le fichier, mpv lit sa couche de base HDR10
        ou SDR. Non vérifié sur ce PC.
      </dd>
    </dl>
    <p class="muted note">
      Un HDR désactivé dans Windows ne veut pas dire que l'écran ne le gère pas :
      il s'active dans Paramètres › Affichage.
    </p>
  {/if}
</section>

<section class="panel" aria-labelledby="settings-about">
  <h2 id="settings-about">À propos</h2>
  <dl>
    {#each rows as row (row.label)}
      <dt>{row.label}</dt>
      <dd>{row.value}</dd>
    {/each}
  </dl>
</section>

<style>
  .panel {
    background: var(--color-surface);
    border-radius: var(--radius-md);
    padding: var(--space-6);
    display: grid;
    gap: var(--space-3);
    justify-items: start;
  }
  h2 {
    margin: 0;
    font-size: 18px;
  }
  p {
    margin: 0;
  }
  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: var(--space-2) var(--space-4);
    margin: 0;
  }
  dt {
    color: var(--color-text-muted);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .muted {
    color: var(--color-text-muted);
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  .note {
    font-size: 13px;
    max-width: 60ch;
  }
  .problem {
    display: grid;
    gap: var(--space-2);
    justify-items: start;
  }
  .problem p {
    color: var(--color-danger);
  }
  button {
    font: inherit;
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
    background: transparent;
    color: var(--color-text);
    border: 1px solid var(--color-elevated);
  }
  button:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
</style>
