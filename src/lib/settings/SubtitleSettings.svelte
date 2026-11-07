<script lang="ts">
import {
  BACKGROUNDS,
  COLORS,
  LANGUAGES,
  type PlaybackPreferences,
  POSITIONS,
  preferenceStorage,
  readPreferences,
  SIZES,
  SUBTITLE_MODES,
  type SubtitleStyle,
  savePreferences,
  type TrackPreferences,
} from "../player/subtitles";

let prefs = $state<PlaybackPreferences>(readPreferences(preferenceStorage()));

function changeTracks(next: Partial<TrackPreferences>) {
  prefs = { ...prefs, tracks: { ...prefs.tracks, ...next } };
  savePreferences(preferenceStorage(), prefs);
}

function changeStyle(next: Partial<SubtitleStyle>) {
  prefs = { ...prefs, subtitleStyle: { ...prefs.subtitleStyle, ...next } };
  savePreferences(preferenceStorage(), prefs);
}

const language = (value: string) => (value === "" ? null : value);
// No language for "forced only" means the audio's; for "always", the server's pick.
const subtitleLanguageHint = $derived(
  prefs.tracks.subtitleMode === "forcedOnly"
    ? "Sans langue choisie : celle de l'audio."
    : prefs.tracks.subtitleMode === "always"
      ? "Sans langue choisie : celle proposée par le serveur."
      : null,
);
</script>

<section class="panel" aria-labelledby="settings-subtitles">
  <h2 id="settings-subtitles">Audio et sous-titres</h2>
  <p class="lead">
    Ces choix restent sur ce poste. Sans piste audio dans la langue demandée, celle
    proposée par le serveur Emby est gardée ; sans sous-titres dans la langue
    demandée, aucun n'est affiché. Pendant la lecture, le menu des pistes permet
    toujours d'en changer.
  </p>

  <div class="fields">
    <label class="field">
      <span>Langue audio</span>
      <select
        value={prefs.tracks.audioLanguage ?? ""}
        onchange={(e) =>
          changeTracks({ audioLanguage: language(e.currentTarget.value) })}
      >
        <option value="">Comme sur le serveur Emby</option>
        {#each LANGUAGES as l (l.code)}
          <option value={l.code}>{l.label}</option>
        {/each}
      </select>
    </label>
    <label class="field">
      <span>Sous-titres</span>
      <select
        value={prefs.tracks.subtitleMode}
        onchange={(e) =>
          changeTracks({
            subtitleMode: e.currentTarget.value as TrackPreferences["subtitleMode"],
          })}
      >
        {#each SUBTITLE_MODES as m (m.value)}
          <option value={m.value}>{m.label}</option>
        {/each}
      </select>
    </label>
    {#if prefs.tracks.subtitleMode === "forcedOnly" || prefs.tracks.subtitleMode === "always"}
      <label class="field">
        <span>Langue des sous-titres</span>
        <select
          value={prefs.tracks.subtitleLanguage ?? ""}
          aria-describedby="subtitle-language-hint"
          onchange={(e) =>
            changeTracks({ subtitleLanguage: language(e.currentTarget.value) })}
        >
          <option value="">Aucune en particulier</option>
          {#each LANGUAGES as l (l.code)}
            <option value={l.code}>{l.label}</option>
          {/each}
        </select>
        <small id="subtitle-language-hint" class="muted">{subtitleLanguageHint}</small>
      </label>
    {/if}
  </div>

  <h3>Apparence des sous-titres texte</h3>
  <div class="fields">
    <label class="field">
      <span>Taille</span>
      <select
        value={prefs.subtitleStyle.size}
        onchange={(e) =>
          changeStyle({ size: e.currentTarget.value as SubtitleStyle["size"] })}
      >
        {#each SIZES as o (o.value)}
          <option value={o.value}>{o.label}</option>
        {/each}
      </select>
    </label>
    <label class="field">
      <span>Couleur</span>
      <select
        value={prefs.subtitleStyle.color}
        onchange={(e) =>
          changeStyle({ color: e.currentTarget.value as SubtitleStyle["color"] })}
      >
        {#each COLORS as o (o.value)}
          <option value={o.value}>{o.label}</option>
        {/each}
      </select>
    </label>
    <label class="field">
      <span>Lisibilité</span>
      <select
        value={prefs.subtitleStyle.background}
        onchange={(e) =>
          changeStyle({
            background: e.currentTarget.value as SubtitleStyle["background"],
          })}
      >
        {#each BACKGROUNDS as o (o.value)}
          <option value={o.value}>{o.label}</option>
        {/each}
      </select>
    </label>
    <label class="field">
      <span>Position</span>
      <select
        value={prefs.subtitleStyle.position}
        onchange={(e) =>
          changeStyle({
            position: e.currentTarget.value as SubtitleStyle["position"],
          })}
      >
        {#each POSITIONS as o (o.value)}
          <option value={o.value}>{o.label}</option>
        {/each}
      </select>
    </label>
  </div>
  <label class="check">
    <input
      type="checkbox"
      checked={prefs.subtitleStyle.overrideAss}
      onchange={(e) => changeStyle({ overrideAss: e.currentTarget.checked })}
    />
    <span>
      Appliquer aussi aux sous-titres stylés (ASS)
      <small class="muted">
        Sinon, ils gardent la police, les couleurs et la place prévues par leur
        auteur. Les sous-titres en image (PGS, DVD) ne changent pas.
      </small>
    </span>
  </label>
  <p class="muted note">S'applique à la prochaine lecture.</p>
</section>

<style>
  /* Same panel as the other sections of Réglages, which cannot reach this one. */
  .panel {
    max-width: 640px;
    margin: var(--space-6) 0;
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
  h3 {
    margin: var(--space-3) 0 0;
    font-size: 15px;
  }
  p {
    margin: 0;
  }
  .lead {
    color: var(--color-text-secondary);
    max-width: 60ch;
  }
  .muted {
    color: var(--color-text-muted);
  }
  .note {
    font-size: 13px;
  }
  .fields {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
  }
  .field {
    display: grid;
    gap: 6px;
    align-content: start;
    font-size: 12px;
    color: var(--color-text-muted);
  }
  .field small {
    font-size: 12px;
    max-width: 30ch;
  }
  /* Glass controls as in the library toolbar; the native list keeps the keyboard. */
  .field select {
    appearance: none;
    min-width: 180px;
    max-width: 260px;
    height: 38px;
    padding: 0 36px 0 14px;
    border: 0;
    border-radius: var(--radius-sm);
    background:
      url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23f5f5f7' stroke-width='2.5' stroke-linecap='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E")
        no-repeat right 14px center,
      var(--fill-glass);
    color: var(--color-text);
    font: inherit;
    font-size: 14px;
    font-weight: 500;
    cursor: pointer;
    transition: background-color var(--motion-fast);
  }
  .field select:hover {
    background-color: var(--fill-glass-hover);
  }
  .field select option {
    background: var(--color-elevated);
    color: var(--color-text);
  }
  .field select:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .check {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    cursor: pointer;
  }
  .check input {
    margin: 3px 0 0;
    width: 18px;
    height: 18px;
    accent-color: var(--color-accent);
    flex: none;
  }
  .check input:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }
  .check small {
    display: block;
    margin-top: 2px;
    font-size: 13px;
    max-width: 58ch;
  }
</style>
