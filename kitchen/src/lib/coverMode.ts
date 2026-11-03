/**
 * How a platform's cover grid fills its frames.
 *
 * The grid used to force every cover into a 3:4 crop, which mangles anything that is not portrait
 * box art. Landscape libraries like the SNES lost most of the picture to it. Both modes now draw
 * the same frame, at the shape that platform's covers actually share; they differ only in what
 * happens to a cover that does not match it.
 */
export type CoverMode = 'crop' | 'fit'

export const COVER_MODES: CoverMode[] = ['crop', 'fit']

/** Drawn when a platform has no art, or none of it could be measured. */
export const DEFAULT_ASPECT = 3 / 4

/** The fewest covers a page holds, before rounding up to a whole row. */
export const MIN_PAGE_SIZE = 20

const KEY_PREFIX = 'cannoli_cover_mode:'

// Per platform rather than global: a library of consistent box art and one of mixed scrapes want
// different answers, and the same user owns both.
export function coverModeKey(tag: string): string {
  return `${KEY_PREFIX}${tag}`
}

export function loadCoverMode(tag: string): CoverMode {
  try {
    const stored = localStorage.getItem(coverModeKey(tag))
    return COVER_MODES.includes(stored as CoverMode) ? (stored as CoverMode) : 'crop'
  } catch {
    return 'crop'
  }
}

export function saveCoverMode(tag: string, mode: CoverMode): void {
  try {
    localStorage.setItem(coverModeKey(tag), mode)
  } catch {
    // A browser refusing storage is not a reason to refuse the mode for this session.
  }
}

/**
 * The shape every card on a platform is drawn at.
 *
 * One shape for the whole grid whichever mode is on, because that is what keeps rows aligned and
 * a page's bottom edge level. Letting each cover keep its own shape was tried and abandoned: with
 * covers dealt across columns, any pattern in the art that divides into the column count stripes
 * the grid, and column heights diverge until one runs off the screen while others end halfway.
 */
export function frameAspect(platformAspect?: number | null): number {
  return platformAspect && platformAspect > 0 ? platformAspect : DEFAULT_ASPECT
}

/**
 * How many covers a page holds: at least [MIN_PAGE_SIZE], rounded up to a whole row.
 *
 * A page ending mid-row leaves a ragged edge. Rounding up rather than down is what keeps the floor
 * a floor. The count therefore moves with the window, because the row it has to fill is as wide as
 * the grid; the last page is still short whenever the library does not divide evenly.
 */
export function pageSizeFor(cols: number, min: number = MIN_PAGE_SIZE): number {
  const c = Math.max(1, Math.floor(cols))
  const floor = Math.max(1, Math.floor(min))
  return c * Math.ceil(floor / c)
}
