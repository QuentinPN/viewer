// A Stremio stream failed to open or broke off: the app asks the page it came
// from to show « Autres sources » again, with a fresh list.
export interface ReopenRequest {
  /**
   * The `key` of the title (`PlaybackStart.itemId` of its stream): the Emby
   * item id, or « stremio:… » for a title outside Emby.
   */
  key: string;
  message: string;
}

class SourcesReopen {
  request = $state<ReopenRequest | null>(null);

  ask(key: string, message: string) {
    this.request = { key, message };
  }

  /** The message for this title, once; null when the request is for another. */
  take(key: string): string | null {
    const r = this.request;
    if (!r || r.key !== key) return null;
    this.request = null;
    return r.message;
  }

  clear() {
    this.request = null;
  }
}

export const sourcesReopen = new SourcesReopen();
