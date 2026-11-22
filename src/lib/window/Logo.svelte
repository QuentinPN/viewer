<script lang="ts">
// App logo, drawn from go-app/build/logo.svg.
let { size = 72 }: { size?: number } = $props();
// Gradient ids must stay unique when several logos are on the page.
const id = $props.id();
</script>

<svg width={size} height={size} viewBox="0 0 1024 1024" aria-hidden="true">
  <defs>
    <linearGradient id="{id}-tile" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#2A3242" />
      <stop offset="1" stop-color="#090B10" />
    </linearGradient>
    <linearGradient id="{id}-mark" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" />
      <stop offset="1" stop-color="#C9D2DE" />
    </linearGradient>
    <linearGradient id="{id}-play" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#8FD3F5" />
      <stop offset="1" stop-color="#3D9BD0" />
    </linearGradient>
  </defs>
  <rect x="32" y="32" width="960" height="960" rx="216" fill="url(#{id}-tile)" />
  <rect
    x="33.5"
    y="33.5"
    width="957"
    height="957"
    rx="214.5"
    fill="none"
    stroke="#FFFFFF"
    stroke-opacity="0.12"
    stroke-width="3"
  />
  <path
    d="M322 268 V548 A190 190 0 0 0 702 548 V268"
    fill="none"
    stroke="url(#{id}-mark)"
    stroke-width="124"
    stroke-linecap="round"
  />
  <path
    d="M462 398 Q462 372 485 385 L598 451 Q620 464 598 477 L485 543 Q462 556 462 530 Z"
    fill="url(#{id}-play)"
  />
</svg>
