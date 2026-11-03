/** Platform name lookup from Scorza's platforms.json */
export const PLATFORM_NAMES: Record<string, string> = {
  GB: 'Game Boy',
  GBC: 'Game Boy Color',
  GBA: 'Game Boy Advance',
  NES: 'Nintendo Entertainment System',
  FDS: 'Famicom Disk System',
  SNES: 'Super Nintendo Entertainment System',
  SATELLAVIEW: 'Satellaview',
  N64DD: 'Nintendo 64DD',
  N64: 'Nintendo 64',
  NDS: 'Nintendo DS',
  GG: 'Game Gear',
  SMS: 'Master System',
  MD: 'Sega Genesis',
  SG1000: 'Sega SG-1000',
  '32X': 'Sega 32X',
  SEGACD: 'Sega CD',
  SATURN: 'Sega Saturn',
  PS: 'PlayStation',
  PSP: 'PSP',
  DC: 'Dreamcast',
  LYNX: 'Atari Lynx',
  JAGUAR: 'Atari Jaguar',
  ATARI2600: 'Atari 2600',
  ATARI5200: 'Atari 5200',
  ATARI7800: 'Atari 7800',
  PCE: 'PC Engine',
  PCECD: 'PC Engine CD',
  PCFX: 'PC-FX',
  NGP: 'Neo Geo Pocket',
  NGPC: 'Neo Geo Pocket Color',
  WS: 'WonderSwan',
  WSC: 'WonderSwan Color',
  NEOGEO: 'Neo Geo',
  CPS1: 'CPS-1',
  CPS2: 'CPS-2',
  CPS3: 'CPS-3',
  MAME: 'Arcade (MAME)',
  FBN: 'Arcade (FBNeo)',
  VIRTUALBOY: 'Virtual Boy',
  POKEMINI: 'Pokemon Mini',
  COLECOVISION: 'ColecoVision',
  VECTREX: 'Vectrex',
  INTELLIVISION: 'Intellivision',
  SUPERGRAFX: 'PC Engine SuperGrafx',
  DOS: 'DOS',
  CD32: 'Amiga CD32',
  CDTV: 'Amiga CDTV',
  MSX: 'MSX',
  NAOMI: 'Naomi',
  ATOMISWAVE: 'Atomiswave',
  NEOGEOCD: 'Neo Geo CD',
  '3DO': '3DO',
  ODYSSEY2: 'Odyssey 2',
  GAMEANDWATCH: 'Game & Watch',
  C64: 'Commodore 64',
  AMSTRADCPC: 'Amstrad CPC',
  ATARIST: 'Atari ST',
  X68000: 'X68000',
  ZX81: 'ZX81',
  SCUMMVM: 'ScummVM',
  AMIGA: 'Amiga',
  PC: 'PC',
  PICO8: 'PICO-8',
  PS2: 'PlayStation 2',
  GC: 'GameCube',
  WII: 'Wii',
  '3DS': 'Nintendo 3DS',
  WIIU: 'Wii U',
  PSVITA: 'PS Vita',
  PS3: 'PlayStation 3',
  NSW: 'Nintendo Switch',
  TOOLS: 'Tools',
  PORTS: 'Ports',
}

/**
 * Tags in release-year order within each group, matching the order of Scorza's platforms.json.
 * Keep the two in step: the launcher is the source of truth and serves this over /api/tags, and a
 * divergence here shows up as the same list reading differently in two places.
 */
const PLATFORM_GROUPS: Record<string, string[]> = {
  Nintendo: ['NES', 'FDS', 'GB', 'SNES', 'SATELLAVIEW', 'VIRTUALBOY', 'N64', 'N64DD', 'GBC', 'GBA', 'GC', 'POKEMINI', 'NDS', 'WII', '3DS', 'WIIU', 'NSW'],
  Sega: ['SG1000', 'SMS', 'MD', 'GG', 'SEGACD', '32X', 'SATURN', 'DC', 'NAOMI'],
  Sony: ['PS', 'PS2', 'PSP', 'PS3', 'PSVITA'],
  Atari: ['ATARI2600', 'ATARI5200', 'ATARI7800', 'LYNX', 'JAGUAR'],
  NEC: ['PCE', 'PCECD', 'SUPERGRAFX', 'PCFX'],
  SNK: ['NEOGEO', 'NEOGEOCD', 'NGP', 'NGPC'],
  Bandai: ['WS', 'WSC'],
  Arcade: ['MAME', 'FBN', 'CPS1', 'CPS2', 'CPS3', 'ATOMISWAVE'],
  Computer: ['DOS', 'AMIGA', 'CD32', 'CDTV', 'MSX', 'C64', 'AMSTRADCPC', 'ATARIST', 'X68000', 'ZX81', 'SCUMMVM', 'PC'],
  Other: ['INTELLIVISION', 'COLECOVISION', 'VECTREX', '3DO', 'ODYSSEY2', 'GAMEANDWATCH', 'PICO8'],
  'Android Apps': ['TOOLS', 'PORTS'],
}

/** Tag to group, from the built-in table. Fallback only; the launcher is the source of truth. */
const FALLBACK_GROUP_OF: Record<string, string> = Object.fromEntries(
  Object.entries(PLATFORM_GROUPS).flatMap(([name, tags]) => tags.map(t => [t, name])),
)

export interface PlatformGroup {
  name: string
  tags: string[]
}

/**
 * Group a list of active tags by manufacturer. When alphabetical, sorts within each group by name.
 * Otherwise preserves definition order (era).
 *
 * `served` is the launcher's own grouping from platforms.json, which is the source of truth. The
 * table below is the fallback for a launcher that predates the field. Keeping both lists in step by
 * hand is exactly the drift this is meant to end, so prefer served whenever it has anything.
 */
export function groupPlatforms(
  tags: string[],
  alphabetical = false,
  served: Record<string, string> = {},
): PlatformGroup[] {
  const groups: PlatformGroup[] = []
  const byName = (a: string, b: string) => platformName(a).localeCompare(platformName(b))
  const useServed = Object.keys(served).length > 0
  const groupFor = (t: string) => (useServed ? served[t] : undefined) ?? FALLBACK_GROUP_OF[t]

  // Order comes from the built-in table either way: it is era order, which the served map, being
  // keyed by tag, cannot express.
  const names = Object.keys(PLATFORM_GROUPS)
  for (const name of names) {
    const matched = tags.filter(t => groupFor(t) === name)
    if (alphabetical) matched.sort(byName)
    else matched.sort((a, b) => (PLATFORM_GROUPS[name]?.indexOf(a) ?? 0) - (PLATFORM_GROUPS[name]?.indexOf(b) ?? 0))
    if (matched.length) groups.push({ name, tags: matched })
  }

  // A group the launcher names that the table does not know, plus anything ungrouped.
  const placed = new Set(groups.flatMap(g => g.tags))
  const leftover = tags.filter(t => !placed.has(t))
  for (const name of [...new Set(leftover.map(t => groupFor(t)).filter((n): n is string => !!n && !names.includes(n)))]) {
    const matched = leftover.filter(t => groupFor(t) === name).sort(byName)
    if (matched.length) groups.push({ name, tags: matched })
  }
  const ungrouped = leftover.filter(t => !groupFor(t)).sort(byName)
  if (ungrouped.length) groups.push({ name: 'Other', tags: ungrouped })

  return groups
}

/** Returns the path to a platform icon SVG, or undefined if there is none. App tags fall through to a generic icon. */
export function platformIcon(tag: string): string | undefined {
  if (isAppTag(tag)) return undefined
  if (PLATFORM_NAMES[tag]) return `/platforms/${tag}.svg`
  return undefined
}

export function platformName(tag: string): string {
  return PLATFORM_NAMES[tag] ?? tag
}

export function platformLabel(tag: string): string {
  const name = PLATFORM_NAMES[tag]
  return name ? `${name} (${tag})` : tag
}

export const APP_TAGS = ['TOOLS', 'PORTS'] as const

export function isAppTag(tag: string): boolean {
  return (APP_TAGS as readonly string[]).includes(tag)
}

/** Tags whose only manageable content is the games themselves, so the platform page drops its tab bar. */
const GAMES_ONLY_TAGS = ['PC']

export function isGamesOnlyTag(tag: string): boolean {
  return GAMES_ONLY_TAGS.includes(tag)
}

const FBNEO_SAMPLE_TAGS = ['FBN']

export function supportsFbneoSamples(tag: string): boolean {
  return FBNEO_SAMPLE_TAGS.includes(tag)
}
