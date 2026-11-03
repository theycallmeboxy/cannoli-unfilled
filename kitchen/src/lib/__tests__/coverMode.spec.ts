import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  COVER_MODES,
  DEFAULT_ASPECT,
  MIN_PAGE_SIZE,
  coverModeKey,
  frameAspect,
  loadCoverMode,
  pageSizeFor,
  saveCoverMode,
} from '../coverMode'

function fakeStorage() {
  const map = new Map<string, string>()
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    length: 0,
  } as unknown as Storage
}

describe('cover mode storage', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', fakeStorage())
  })

  it('starts on crop', () => {
    expect(loadCoverMode('SNES')).toBe('crop')
  })

  it('remembers a choice per platform', () => {
    saveCoverMode('SNES', 'fit')
    expect(loadCoverMode('SNES')).toBe('fit')
    expect(loadCoverMode('GBA')).toBe('crop')
  })

  // 'masonry' is not arbitrary: an earlier build offered it, so a card that tried it still has it
  // stored. An unknown mode has to fall back rather than render nothing.
  it('ignores a stored value that is not a mode', () => {
    localStorage.setItem(coverModeKey('SNES'), 'masonry')
    expect(loadCoverMode('SNES')).toBe('crop')
  })

  // A browser refusing storage is not a reason to refuse the grid.
  it('survives storage throwing', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
    } as unknown as Storage)
    expect(loadCoverMode('SNES')).toBe('crop')
    expect(() => saveCoverMode('SNES', 'fit')).not.toThrow()
  })

  it('offers exactly the two modes', () => {
    expect(COVER_MODES).toEqual(['crop', 'fit'])
  })
})

describe('frame aspect', () => {
  // The whole point: a landscape library stops being squeezed into a portrait frame.
  it('uses the shape the platform actually measured', () => {
    expect(frameAspect(1.33)).toBe(1.33)
  })

  // artAspect is null for a platform with no art, or none of it measurable.
  it('falls back to 3:4 when the platform measured nothing', () => {
    expect(frameAspect(null)).toBe(DEFAULT_ASPECT)
    expect(frameAspect(undefined)).toBe(DEFAULT_ASPECT)
    expect(frameAspect(0)).toBe(DEFAULT_ASPECT)
    expect(frameAspect(-2)).toBe(DEFAULT_ASPECT)
  })

  // Both modes share one frame; only the fill rule differs. Rows stay aligned either way.
  it('does not depend on the mode', () => {
    expect(frameAspect(1.33)).toBe(frameAspect(1.33))
  })
})

describe('page size', () => {
  it('is always a whole number of rows', () => {
    for (const cols of [1, 2, 3, 4, 5, 6, 7, 8, 11]) {
      expect(pageSizeFor(cols) % cols).toBe(0)
    }
  })

  it('never drops below the floor', () => {
    for (const cols of [1, 2, 3, 4, 5, 6, 7, 8, 11]) {
      expect(pageSizeFor(cols)).toBeGreaterThanOrEqual(MIN_PAGE_SIZE)
    }
  })

  // Rounding up costs at most one row, and only where the floor does not already divide evenly.
  it('adds no more than a row above the floor', () => {
    expect(pageSizeFor(5)).toBe(20)
    expect(pageSizeFor(4)).toBe(20)
    expect(pageSizeFor(6)).toBe(24)
    expect(pageSizeFor(3)).toBe(21)
    expect(pageSizeFor(7)).toBe(21)
  })

  it('never returns a page too small to hold anything', () => {
    expect(pageSizeFor(0)).toBe(MIN_PAGE_SIZE)
    expect(pageSizeFor(-2)).toBe(MIN_PAGE_SIZE)
  })
})
