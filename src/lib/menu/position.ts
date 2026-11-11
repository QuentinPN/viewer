// Where the card menu opens (ADR 0100): next to what opened it, flipped or
// shifted so it stays inside the window.

export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface Size {
  width: number;
  height: number;
}

/** Space kept between the menu and the window's edges. */
export const EDGE = 8;
/** Space between the menu and its anchor. */
const GAP = 6;

/**
 * Top-left corner of a menu of `menu` size for `anchor` (a button, or a 0×0
 * box at the pointer): below and left-aligned, else above, else right-aligned,
 * and finally clamped to the window. A menu taller than the window starts at
 * its top edge; the caller scrolls it. `minTop` keeps it under the title bar.
 */
export function placeMenu(
  anchor: Box,
  menu: Size,
  viewport: Size,
  minTop = EDGE,
): { left: number; top: number } {
  const bottom = anchor.top + anchor.height;
  const right = anchor.left + anchor.width;

  let top = bottom + GAP;
  if (top + menu.height > viewport.height - EDGE) {
    const above = anchor.top - GAP - menu.height;
    top = above >= minTop ? above : viewport.height - EDGE - menu.height;
  }

  let left = anchor.left;
  if (left + menu.width > viewport.width - EDGE) left = right - menu.width;

  return {
    left: clamp(left, EDGE, viewport.width - EDGE - menu.width),
    top: clamp(top, minTop, viewport.height - EDGE - menu.height),
  };
}

/** `value` within [min, max]; `min` wins when the range is empty. */
function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(value, max));
}
