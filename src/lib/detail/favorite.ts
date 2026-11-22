// Favorite toggle of the detail page.

/**
 * Optimistic change: `show` gets the wanted state at once, then the state the
 * server reports, or the previous state back when the request fails (the error
 * is rethrown for the caller to report).
 */
export async function changeFavorite(
  wanted: boolean,
  show: (favorite: boolean) => void,
  send: (favorite: boolean) => Promise<boolean>,
): Promise<boolean> {
  show(wanted);
  try {
    const favorite = await send(wanted);
    show(favorite);
    return favorite;
  } catch (e) {
    show(!wanted);
    throw e;
  }
}
