// Next episode offered at the end of an episode.
import type { EpisodeLink, PlaybackTitle } from "../emby/types";

/** Seconds before the next episode starts on its own. */
export const UP_NEXT_SECONDS = 10;

/** Title of the next episode, in the same series as the one that ended. */
export function nextTitle(
  link: EpisodeLink,
  current: PlaybackTitle | null,
): PlaybackTitle {
  return {
    name: link.name,
    seriesName: current?.seriesName ?? null,
    seasonIndex: link.seasonIndex,
    index: link.index,
    indexEnd: link.indexEnd,
  };
}

/** Whether a playback is an episode, the only kind with a next one. */
export function isEpisode(title: PlaybackTitle | null): boolean {
  return title?.seriesName != null;
}

/**
 * Counts down once a second from `seconds`: `onTick` gets the seconds left,
 * `onDone` runs at 0. Returns a function that stops it; stopping twice is harmless.
 */
export function startCountdown(
  seconds: number,
  onTick: (left: number) => void,
  onDone: () => void,
): () => void {
  let left = seconds;
  const timer = setInterval(() => {
    left -= 1;
    onTick(left);
    if (left <= 0) {
      clearInterval(timer);
      onDone();
    }
  }, 1000);
  return () => clearInterval(timer);
}
