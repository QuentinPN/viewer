// A Stremio stream failed to open or broke off: the app asks the detail page
// it came from to show « Autres sources » again, with a fresh list.
export interface ReopenRequest {
  /** The Emby item of the page. */
  itemId: string;
  message: string;
}

class SourcesReopen {
  request = $state<ReopenRequest | null>(null);

  ask(itemId: string, message: string) {
    this.request = { itemId, message };
  }

  /** The message for this page, once; null when the request is for another. */
  take(itemId: string): string | null {
    const r = this.request;
    if (!r || r.itemId !== itemId) return null;
    this.request = null;
    return r.message;
  }

  clear() {
    this.request = null;
  }
}

export const sourcesReopen = new SourcesReopen();
