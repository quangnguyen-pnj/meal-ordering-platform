/**
 * The one surface every text field, select and number box shares. Fields are the place the UI
 * still needs a visible edge (WCAG 1.4.11: people must see where to type), so they get a soft
 * fill plus a faint inset ring instead of a hard border; focus swaps it for the brand ring.
 * The fill is --field: white on the page background, a muted tint on a card or in a dialog.
 */
export const fieldSurface =
  'rounded-md bg-[hsl(var(--field))] ring-1 ring-inset ring-border transition-[background-color,box-shadow] hover:bg-muted focus-visible:bg-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 aria-[invalid=true]:ring-destructive';
