<script lang="ts">
import { onMount } from "svelte";
import { window as appWindow } from "../native";

interface Props {
  /** Over the video: no background, the player's own gradient shows behind it. */
  overVideo?: boolean;
}

let { overVideo = false }: Props = $props();

let maximized = $state(false);

onMount(() => {
  const sync = () =>
    void appWindow.isMaximized().then(
      (m) => (maximized = m),
      () => {},
    );
  sync();
  const unlisten = appWindow.onResized(sync);
  return () => void unlisten.then((stop) => stop());
});
</script>

<!-- Replaces the Windows title bar (ADR 0029). The empty area drags the window and
     double-clicking it maximizes; the buttons keep Windows' order and glyphs.
     data-tauri-drag-region is Tauri-specific: the Wails port uses the CSS
     `--wails-draggable: drag` instead. -->
<div class="titlebar" class:over-video={overVideo} data-tauri-drag-region>
  <div class="controls">
    <button
      type="button"
      aria-label="Réduire"
      title="Réduire"
      tabindex="-1"
      onclick={() => void appWindow.minimize()}
    >
      <span aria-hidden="true">&#xE921;</span>
    </button>
    <button
      type="button"
      aria-label={maximized ? "Niveau inférieur" : "Agrandir"}
      title={maximized ? "Niveau inférieur" : "Agrandir"}
      tabindex="-1"
      onclick={() => void appWindow.toggleMaximize()}
    >
      <span aria-hidden="true">{maximized ? "" : ""}</span>
    </button>
    <button
      type="button"
      class="close"
      aria-label="Fermer"
      title="Fermer"
      tabindex="-1"
      onclick={() => void appWindow.close()}
    >
      <span aria-hidden="true">&#xE8BB;</span>
    </button>
  </div>
</div>

<style>
  .titlebar {
    position: fixed;
    inset: 0 0 auto 0;
    z-index: 1000;
    height: var(--titlebar-height);
    display: flex;
    justify-content: flex-end;
    background: var(--color-bg);
    user-select: none;
  }
  .titlebar.over-video {
    background: transparent;
  }
  .controls {
    display: flex;
  }
  /* Windows' caption buttons: 46 px wide, Segoe Fluent Icons (Windows 11) or Segoe
     MDL2 Assets (Windows 10) glyphs at 10 px. */
  button {
    width: 46px;
    height: 100%;
    border: 0;
    padding: 0;
    background: transparent;
    color: var(--color-text);
    font-family: "Segoe Fluent Icons", "Segoe MDL2 Assets";
    font-size: 10px;
    transition: background-color var(--motion-fast) ease;
  }
  button:hover {
    background: rgb(255 255 255 / 10%);
  }
  button:active {
    background: rgb(255 255 255 / 6%);
  }
  button.close:hover {
    background: #c42b1c;
    color: #fff;
  }
  button.close:active {
    background: rgb(196 43 28 / 80%);
  }
</style>
