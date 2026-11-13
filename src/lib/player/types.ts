// Mirrors src-tauri/src/player/mod.rs (serde camelCase, tagged by "type").

export type TrackKind = "video" | "audio" | "sub";

export interface Track {
  id: number;
  kind: TrackKind;
  title: string | null;
  lang: string | null;
  codec: string | null;
  selected: boolean;
  external: boolean;
  default: boolean;
}

export interface PlaybackRequest {
  source: string;
  httpHeaders?: [string, string][];
  startSeconds?: number | null;
}

export type PlayerCommand =
  | { type: "setPaused"; paused: boolean }
  | { type: "togglePause" }
  | { type: "seekAbsolute"; seconds: number }
  | { type: "seekRelative"; seconds: number }
  | { type: "setAudioTrack"; id: number | null }
  | { type: "setSubtitleTrack"; id: number | null }
  | { type: "setVolume"; percent: number }
  | { type: "setSpeed"; speed: number }
  | { type: "setNightMode"; on: boolean }
  | { type: "setSubtitleDelay"; seconds: number }
  | { type: "setAudioDelay"; seconds: number }
  | { type: "setVideoFit"; fit: "original" | "fill" | "stretch" }
  | { type: "stop" };

export type EndReason = "eof" | "stopped" | "quit" | "error" | "other";

export type PlayerEvent =
  | { type: "fileLoaded" }
  | { type: "timePos"; seconds: number }
  | { type: "duration"; seconds: number }
  | { type: "paused"; paused: boolean }
  | { type: "buffering"; active: boolean }
  | { type: "tracks"; tracks: Track[] }
  | { type: "videoReconfigured" }
  | { type: "seeked" }
  | { type: "endFile"; reason: EndReason; error: string | null }
  | { type: "shutdown" };

export interface PlayerDiagnostics {
  mpvVersion: string | null;
  ffmpegVersion: string | null;
  videoOutput: string | null;
  gpuApi: string | null;
  hwdecCurrent: string | null;
  videoCodec: string | null;
  videoWidth: number | null;
  videoHeight: number | null;
  audioCodec: string | null;
  audioOutput: string | null;
  container: string | null;
  osdWidth: number | null;
  osdHeight: number | null;
  timePos: number | null;
  duration: number | null;
  paused: boolean | null;
  /** Engine volume in percent (ADR 0017). */
  volume: number | null;
  frameDropCount: number | null;
  decoderFrameDropCount: number | null;
  /** Chapters mpv found in the file (ADR 0034). */
  chapterCount: number | null;
  /** Subtitle options in effect, as mpv reads them back (ADR 0035). */
  subtitleOptions: [string, string][];
}

export interface AppInfo {
  version: string;
  surfaceMode: "child" | "parent" | "separate";
  libmpvFound: boolean;
  /** Debug builds only: the Gate 1 scenario drives the player. */
  spike: boolean;
}
