<script lang="ts">
// Timeline of a request (`RequestDetail.events`), oldest step first.
import { eventDate, eventLabel } from "./labels";
import type { RequestEvent } from "./types";

interface Props {
  events: RequestEvent[];
}

let { events }: Props = $props();
</script>

<ol class="timeline" aria-label="Historique de la demande">
  {#each events as e (e.id)}
    <li>
      <span class="dot" aria-hidden="true"></span>
      <span class="what">{eventLabel(e.type)}</span>
      <span class="when ur-muted">
        {#if e.actor}{e.actor.name} · {/if}{eventDate(e.createdAt)}
      </span>
      {#if e.detail}<span class="detail">{e.detail}</span>{/if}
    </li>
  {/each}
</ol>

<style>
  .timeline {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--space-4);
  }
  li {
    position: relative;
    display: grid;
    gap: 2px;
    padding-left: var(--space-6);
  }
  li:not(:last-child)::after {
    content: "";
    position: absolute;
    left: 5px;
    top: 16px;
    bottom: calc(-1 * var(--space-4) + 2px);
    width: 2px;
    background: var(--separator);
  }
  .dot {
    position: absolute;
    left: 0;
    top: 5px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: var(--color-elevated);
    border: 2px solid var(--color-accent);
  }
  .what {
    font-weight: 600;
  }
  .when,
  .detail {
    font-size: 13px;
  }
  .detail {
    color: var(--color-text-secondary);
  }
</style>
