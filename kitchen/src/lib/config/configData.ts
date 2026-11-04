/**
 * What the config editor knows besides the file being edited: the other config files, the
 * templates and what the device reports (installed apps, cores, ROM folders, games). Loaded once
 * per session and again after a save or a reload, and indexed for the suggestions and checks.
 */

import { getConfigFile, getConfigHints, type ConfigHints } from '@/api/client'
import { BUILT_IN_NAMES } from './schema'

export type { ConfigHints }

export const REAL_FILES = ['emulators.json', 'systems.json', 'shortcuts.json', 'directory.json', 'overrides.json'] as const
export const TEMPLATE_FILES = ['emulators.template.json', 'systems.template.json', 'shortcuts.template.json'] as const

export interface ConfigData {
  /** Each readable file, parsed; a file that is missing or not valid JSON is left out. */
  files: Record<string, unknown>
  hints: ConfigHints | null
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const str = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() ? v.trim() : undefined)

let cache: Promise<ConfigData> | null = null

/** The data, from this session's cache unless [fresh]. Never rejects: what fails is left out. */
export function loadConfigData(fresh = false): Promise<ConfigData> {
  if (!cache || fresh) {
    cache = (async () => {
      const names = [...REAL_FILES, ...TEMPLATE_FILES]
      const [texts, hints] = await Promise.all([
        Promise.all(names.map(n => getConfigFile(n).then(f => f.text, () => null))),
        getConfigHints().catch(() => null),
      ])
      const files: Record<string, unknown> = {}
      names.forEach((n, i) => {
        const text = texts[i]
        if (text == null) return
        try {
          files[n] = JSON.parse(text)
        } catch {
          // Left out: the editor shows that file's own error when it is opened.
        }
      })
      return { files, hints }
    })()
  }
  return cache
}

// --- Index ---

export interface EmulatorInfo {
  key: string
  name: string
  package?: string
  note?: string
  /** Placeholders its extras use that a system must fill (not the built-in ones). */
  params: string[]
  entry: Obj
}

export interface SystemInfo {
  key: string
  name: string
  /** Its ROM folder as written, defaulting to the key. */
  roms: string
  extensions: string[]
  romm: string[]
  /** Its emulator choices, as overrides.json names them, with the emulator each one uses. */
  choices: { name: string; emulator: string; core?: string }[]
  note?: string
  entry: Obj
}

export interface ShortcutInfo {
  key: string
  name: string
  package?: string
  note?: string
  entry: Obj
}

export interface ConfigIndex {
  emulators: Map<string, EmulatorInfo>
  templateEmulators: Map<string, EmulatorInfo>
  systems: Map<string, SystemInfo>
  templateSystems: Map<string, SystemInfo>
  shortcuts: Map<string, ShortcutInfo>
  templateShortcuts: Map<string, ShortcutInfo>
  /** Core ID → the template systems that use it. */
  templateCores: Map<string, string[]>
  /** RomM slug → the template systems that list it. */
  rommSlugs: Map<string, string[]>
  /** File type → the template systems that list it. */
  extensions: Map<string, string[]>
  /** Intent actions used in the templates, with the packages using each. */
  actions: Map<string, Set<string>>
  /** Package → activities the templates start in it. */
  activities: Map<string, Set<string>>
  /** Package → extra keys the templates send it. */
  extraKeys: Map<string, Set<string>>
  /** Placeholder values systems give, beyond the built-in ones (such as core), with examples. */
  params: Map<string, Set<string>>
  /** Installed apps, package → label; empty when the device didn't say. */
  apps: Map<string, string>
  /** Package → cores it reported. */
  deviceCores: Map<string, string[]>
  romFolders: string[]
  games: string[]
  hasHints: boolean
}

const PLACEHOLDER = /\{([A-Za-z0-9_]+)\}/g

function placeholdersIn(entry: Obj): string[] {
  const out = new Set<string>()
  const extras = Array.isArray(entry.extras) ? entry.extras : []
  for (const x of extras) {
    if (!isObj(x)) continue
    const texts = [x.value, ...(Array.isArray(x.values) ? x.values : [])]
    for (const t of texts) {
      if (typeof t !== 'string') continue
      for (const m of t.matchAll(PLACEHOLDER)) if (!BUILT_IN_NAMES.has(m[1]!)) out.add(m[1]!)
    }
  }
  return [...out]
}

function entriesOf(file: unknown): [string, Obj][] {
  if (!isObj(file)) return []
  return Object.entries(file).filter((e): e is [string, Obj] => !e[0].startsWith('_') && isObj(e[1]))
}

function emulatorsOf(file: unknown): Map<string, EmulatorInfo> {
  const out = new Map<string, EmulatorInfo>()
  for (const [key, e] of entriesOf(file)) {
    out.set(key, { key, name: str(e.name) ?? key, package: str(e.package), note: str(e._note), params: placeholdersIn(e), entry: e })
  }
  return out
}

function shortcutsOf(file: unknown): Map<string, ShortcutInfo> {
  const out = new Map<string, ShortcutInfo>()
  for (const [key, e] of entriesOf(file)) {
    out.set(key, { key, name: str(e.name) ?? key, package: str(e.package), note: str(e._note), entry: e })
  }
  return out
}

/** The name overrides.json uses for one item of a system's `emulators` list. */
export function choiceName(item: unknown): string | undefined {
  if (typeof item === 'string') return str(item)
  if (isObj(item)) return str(item.name) ?? str(item.emulator)
  return undefined
}

function systemsOf(file: unknown): Map<string, SystemInfo> {
  const out = new Map<string, SystemInfo>()
  for (const [key, s] of entriesOf(file)) {
    const list = Array.isArray(s.emulators) ? s.emulators : []
    const choices: SystemInfo['choices'] = []
    for (const item of list) {
      const name = choiceName(item)
      const emulator = typeof item === 'string' ? str(item) : isObj(item) ? str(item.emulator) : undefined
      if (name && emulator) choices.push({ name, emulator, core: isObj(item) ? str(item.core) : undefined })
    }
    const romm = typeof s.romm === 'string' ? [s.romm] : Array.isArray(s.romm) ? s.romm.filter((v): v is string => typeof v === 'string') : []
    out.set(key, {
      key,
      name: str(s.name) ?? key,
      roms: str(s.roms) ?? key,
      extensions: Array.isArray(s.extensions) ? s.extensions.filter((v): v is string => typeof v === 'string') : [],
      romm,
      choices,
      note: str(s._note),
      entry: s,
    })
  }
  return out
}

function add<K, V>(map: Map<K, V[]>, key: K, value: V) {
  const list = map.get(key)
  if (list) {
    if (!list.includes(value)) list.push(value)
  } else map.set(key, [value])
}
function addSet<K, V>(map: Map<K, Set<V>>, key: K, value: V) {
  const set = map.get(key)
  if (set) set.add(value)
  else map.set(key, new Set([value]))
}

const indexes = new WeakMap<ConfigData, ConfigIndex>()

/** The lookups suggestions and checks use, built once per [data]. */
export function indexOf(data: ConfigData): ConfigIndex {
  const known = indexes.get(data)
  if (known) return known
  const f = data.files
  const templateSystems = systemsOf(f['systems.template.json'])
  const systems = systemsOf(f['systems.json'])
  const templateEmulators = emulatorsOf(f['emulators.template.json'])
  const index: ConfigIndex = {
    emulators: emulatorsOf(f['emulators.json']),
    templateEmulators,
    systems,
    templateSystems,
    shortcuts: shortcutsOf(f['shortcuts.json']),
    templateShortcuts: shortcutsOf(f['shortcuts.template.json']),
    templateCores: new Map(),
    rommSlugs: new Map(),
    extensions: new Map(),
    actions: new Map(),
    activities: new Map(),
    extraKeys: new Map(),
    params: new Map(),
    apps: new Map((data.hints?.apps ?? []).map(a => [a.package, a.label])),
    deviceCores: new Map(Object.entries(data.hints?.cores ?? {})),
    romFolders: data.hints?.romFolders ?? [],
    games: data.hints?.games ?? [],
    hasHints: data.hints != null,
  }
  for (const sys of templateSystems.values()) {
    for (const c of sys.choices) if (c.core) add(index.templateCores, c.core, sys.key)
    for (const slug of sys.romm) add(index.rommSlugs, slug.toLowerCase(), sys.key)
    for (const ext of sys.extensions) add(index.extensions, ext.toLowerCase(), sys.key)
  }
  for (const sys of [...templateSystems.values(), ...systems.values()]) {
    for (const item of Array.isArray(sys.entry.emulators) ? sys.entry.emulators : []) {
      if (!isObj(item)) continue
      for (const [k, v] of Object.entries(item)) {
        if (k === 'emulator' || k === 'name' || k.startsWith('_') || BUILT_IN_NAMES.has(k)) continue
        addSet(index.params, k, String(v))
      }
    }
  }
  const intents = [...templateEmulators.values(), ...index.templateShortcuts.values(), ...index.emulators.values(), ...index.shortcuts.values()]
  for (const e of intents) {
    const pkg = e.package ?? ''
    const action = str(e.entry.action)
    if (action) addSet(index.actions, action, pkg)
    const activity = str(e.entry.activity)
    if (activity && pkg) addSet(index.activities, pkg, activity)
    for (const x of Array.isArray(e.entry.extras) ? e.entry.extras : []) {
      if (isObj(x) && str(x.key)) addSet(index.extraKeys, pkg, str(x.key)!)
    }
  }
  indexes.set(data, index)
  return index
}

/** The user's emulator [key], or the template's, saying which. */
export function findEmulator(index: ConfigIndex, key: string): { info: EmulatorInfo; source: 'file' | 'template' } | null {
  const own = index.emulators.get(key)
  if (own) return { info: own, source: 'file' }
  const tpl = index.templateEmulators.get(key)
  return tpl ? { info: tpl, source: 'template' } : null
}

/**
 * The system whose ROM folder holds the game [gameKey] (an overrides.json key): the longest
 * folder it starts with, ignoring case, as the app matches them.
 */
export function systemForGame(index: ConfigIndex, gameKey: string): SystemInfo | null {
  let best: SystemInfo | null = null
  let bestLength = 0
  const lower = gameKey.toLowerCase()
  for (const sys of index.systems.values()) {
    const folder = sys.roms.replace(/\/+$/, '')
    const prefix = (folder.startsWith('/') ? folder : folder.replace(/^\/+/, '')).toLowerCase() + '/'
    if (lower.startsWith(prefix) && prefix.length > bestLength) {
      best = sys
      bestLength = prefix.length
    }
  }
  return best
}

export function emptyData(): ConfigData {
  return { files: {}, hints: null }
}
