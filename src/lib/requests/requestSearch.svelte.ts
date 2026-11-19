// One Request search as the field changes: waits for a pause in typing, then
// keeps only the answer to the latest query (library search and Demander tab).
import { isSearchable, SEARCH_DEBOUNCE_MS } from "../search/query";
import { requestsApi, toRequestsError } from "./api";
import { latestOnly } from "./searchMerge";
import type { MediaSummary, RequestsError } from "./types";

export class RequestSearch {
  results = $state<MediaSummary[] | null>(null);
  error = $state<RequestsError | null>(null);
  loading = $state(false);
  #calls = latestOnly();
  #timer: ReturnType<typeof setTimeout> | undefined;

  /** Searches `query` after the debounce; a shorter one clears the results. */
  set(query: string) {
    clearTimeout(this.#timer);
    const ticket = this.#calls.next();
    if (!isSearchable(query)) {
      this.results = null;
      this.error = null;
      this.loading = false;
      return;
    }
    this.loading = true;
    this.#timer = setTimeout(
      () => void this.#run(query, ticket),
      SEARCH_DEBOUNCE_MS,
    );
  }

  async #run(query: string, ticket: number) {
    try {
      const answer = await requestsApi.search(query);
      if (!this.#calls.isCurrent(ticket)) return;
      this.results = answer.results;
      this.error = null;
    } catch (e) {
      if (!this.#calls.isCurrent(ticket)) return;
      this.error = toRequestsError(e);
    } finally {
      if (this.#calls.isCurrent(ticket)) this.loading = false;
    }
  }

  /** Stops the pending search; a late answer is dropped. */
  stop() {
    clearTimeout(this.#timer);
    this.#calls.next();
  }
}
