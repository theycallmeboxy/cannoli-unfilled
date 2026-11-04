/**
 * Helpers for the config editor: checking JSON text, reading entries from a template, and adding
 * template entries to the real config files without touching anything already there.
 *
 * Keys starting with `_` are comments in every Cannoli config file, so they are never offered as
 * entries and never copied on their own (an entry keeps its own `_note`).
 */

export type JsonObject = Record<string, unknown>

/** The real file a template's entries belong in, or null for a name that isn't a template. */
export function templateTarget(name: string): string | null {
  const m = /^(.+)\.template\.json$/.exec(name)
  return m ? `${m[1]}.json` : null
}

export function isTemplate(name: string): boolean {
  return templateTarget(name) !== null
}

export interface JsonCheck {
  ok: boolean
  message?: string
  /** 1-based line and column of the error, when the browser says where it is. */
  line?: number
  column?: number
  /** 0-based offset into the text, when known. */
  offset?: number
}

function lineColumnOf(text: string, offset: number): { line: number; column: number } {
  const before = text.slice(0, offset)
  const line = before.split('\n').length
  const column = offset - before.lastIndexOf('\n')
  return { line, column }
}

/**
 * Where a JSON text first goes wrong, found by a small scanner of its own: browsers word
 * JSON.parse errors differently and Safari gives no position at all. Returns null for valid JSON.
 */
export function findJsonError(text: string): { offset: number; reason: string } | null {
  let i = 0
  const fail = (reason: string): never => {
    throw { offset: i, reason }
  }
  const ws = () => {
    while (i < text.length && ' \t\n\r'.includes(text[i]!)) i++
  }
  const literal = (word: string) => {
    if (text.startsWith(word, i)) i += word.length
    else fail('unexpected character')
  }
  const string = () => {
    i++ // opening quote
    while (i < text.length) {
      const c = text[i]!
      if (c === '"') { i++; return }
      if (c === '\\') {
        const e = text[i + 1]
        if (e === 'u') {
          if (!/^[0-9a-fA-F]{4}$/.test(text.slice(i + 2, i + 6))) { i++; fail('bad \\u escape') }
          i += 6
        } else if (e !== undefined && '"\\/bfnrt'.includes(e)) {
          i += 2
        } else {
          i++
          fail('bad escape')
        }
      } else if (c < ' ') {
        fail(c === '\n' ? 'line break inside a string (missing closing quote?)' : 'control character inside a string')
      } else {
        i++
      }
    }
    fail('missing closing quote')
  }
  const number = () => {
    const m = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i))
    if (!m || !m[0] || m[0] === '-') fail('bad number')
    i += m![0].length
  }
  const value = (): void => {
    ws()
    const c = text[i]
    if (c === undefined) fail('unexpected end of text')
    else if (c === '{') object()
    else if (c === '[') array()
    else if (c === '"') string()
    else if (c === 't') literal('true')
    else if (c === 'f') literal('false')
    else if (c === 'n') literal('null')
    else if (c === '-' || (c >= '0' && c <= '9')) number()
    else fail(c === "'" ? 'strings need double quotes' : 'unexpected character')
  }
  const object = () => {
    i++
    ws()
    if (text[i] === '}') { i++; return }
    for (;;) {
      ws()
      if (text[i] !== '"') fail(text[i] === '}' ? 'trailing comma' : 'expected a "key"')
      string()
      ws()
      if (text[i] !== ':') fail('expected ":"')
      i++
      value()
      ws()
      if (text[i] === ',') { i++; continue }
      if (text[i] === '}') { i++; return }
      fail(i >= text.length ? 'unexpected end of text' : 'expected "," or "}"')
    }
  }
  const array = () => {
    i++
    ws()
    if (text[i] === ']') { i++; return }
    for (;;) {
      ws()
      if (text[i] === ']') fail('trailing comma')
      value()
      ws()
      if (text[i] === ',') { i++; continue }
      if (text[i] === ']') { i++; return }
      fail(i >= text.length ? 'unexpected end of text' : 'expected "," or "]"')
    }
  }
  try {
    value()
    ws()
    if (i < text.length) fail('unexpected text after the end')
    return null
  } catch (e) {
    if (e && typeof e === 'object' && 'offset' in e) return e as { offset: number; reason: string }
    throw e
  }
}

/** Parses [text] as JSON and, when it fails, says where and why. */
export function checkJson(text: string): JsonCheck {
  try {
    JSON.parse(text)
    return { ok: true }
  } catch (e) {
    const found = findJsonError(text)
    const engine = e instanceof Error ? e.message : String(e)
    if (!found) return { ok: false, message: engine }
    const offset = Math.min(found.offset, text.length)
    return { ok: false, message: found.reason, offset, ...lineColumnOf(text, offset) }
  }
}

/** Parses a config file that must be a JSON object. Empty text counts as an empty object. */
export function parseObject(text: string): JsonObject {
  if (!text.trim()) return {}
  const value: unknown = JSON.parse(text)
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('the file is not a JSON object')
  }
  return value as JsonObject
}

/** Two-space JSON with a final newline, the way Cannoli writes its own files. */
export function formatJson(value: unknown): string {
  return `${JSON.stringify(value, null, 2)}\n`
}

export interface TemplateEntry {
  key: string
  name: string
  note: string
  /** For a system: the emulators its `emulators` list names, in order, without repeats. */
  emulators: string[]
}

function noteOf(entry: JsonObject): string {
  for (const k of ['_note', '_notes', '_about']) {
    const v = entry[k]
    if (typeof v === 'string') return v
    if (Array.isArray(v)) return v.filter(x => typeof x === 'string').join(' ')
  }
  return ''
}

/** The emulator keys a system's `emulators` list refers to: plain names and `{ "emulator": ... }`. */
export function emulatorRefs(system: unknown): string[] {
  if (!system || typeof system !== 'object') return []
  const list = (system as JsonObject).emulators
  if (!Array.isArray(list)) return []
  const refs: string[] = []
  for (const item of list) {
    const key = typeof item === 'string'
      ? item
      : item && typeof item === 'object' && typeof (item as JsonObject).emulator === 'string'
        ? ((item as JsonObject).emulator as string)
        : null
    if (key && !refs.includes(key)) refs.push(key)
  }
  return refs
}

/** Every entry of a template, in file order, skipping comment keys and anything not an object. */
export function templateEntries(template: JsonObject): TemplateEntry[] {
  return Object.entries(template)
    .filter(([key, value]) => !key.startsWith('_') && value !== null && typeof value === 'object' && !Array.isArray(value))
    .map(([key, value]) => {
      const entry = value as JsonObject
      return {
        key,
        name: typeof entry.name === 'string' && entry.name ? entry.name : key,
        note: noteOf(entry),
        emulators: emulatorRefs(entry),
      }
    })
}

export interface MergeResult {
  text: string
  added: string[]
  /** Keys the file already had, left exactly as they were. */
  skipped: string[]
  /** Keys asked for that the template doesn't have. */
  missing: string[]
}

/**
 * Adds the [keys] entries of [template] to the file [targetText], at its end. An entry already in
 * the file is never overwritten. The file is rewritten with two-space indents.
 */
export function mergeEntries(targetText: string, template: JsonObject, keys: string[]): MergeResult {
  const target = parseObject(targetText)
  const added: string[] = []
  const skipped: string[] = []
  const missing: string[] = []
  for (const key of keys) {
    if (key.startsWith('_')) continue
    if (Object.prototype.hasOwnProperty.call(target, key)) {
      skipped.push(key)
    } else if (!Object.prototype.hasOwnProperty.call(template, key)) {
      missing.push(key)
    } else {
      target[key] = structuredCloneJson(template[key])
      added.push(key)
    }
  }
  return { text: added.length ? formatJson(target) : targetText, added, skipped, missing }
}

function structuredCloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

/**
 * The emulators that [systemKeys] (entries of the systems template) use and [emulatorsText]
 * doesn't have yet. `inTemplate` can be copied from the emulators template; `unknown` can't.
 */
export function missingEmulators(
  systemsTemplate: JsonObject,
  systemKeys: string[],
  emulatorsText: string,
  emulatorsTemplate: JsonObject,
): { inTemplate: string[]; unknown: string[] } {
  const have = parseObject(emulatorsText)
  const inTemplate: string[] = []
  const unknown: string[] = []
  for (const key of systemKeys) {
    for (const ref of emulatorRefs(systemsTemplate[key])) {
      if (Object.prototype.hasOwnProperty.call(have, ref)) continue
      if (inTemplate.includes(ref) || unknown.includes(ref)) continue
      if (Object.prototype.hasOwnProperty.call(emulatorsTemplate, ref)) inTemplate.push(ref)
      else unknown.push(ref)
    }
  }
  return { inTemplate, unknown }
}

export type MenuKind = 'system' | 'shortcut'

/** Every `{ "<kind>": key }` placed anywhere in a directory tree. */
function placed(items: unknown, kind: MenuKind, into: Set<string>) {
  if (!Array.isArray(items)) return
  for (const item of items) {
    if (!item || typeof item !== 'object') continue
    const obj = item as JsonObject
    if (typeof obj[kind] === 'string') into.add(obj[kind] as string)
    placed(obj.items, kind, into)
  }
}

/**
 * Appends `{ "<kind>": key }` to the top-level `items` of directory.json for each key the
 * directory doesn't place anywhere yet.
 */
export function placeInDirectory(directoryText: string, kind: MenuKind, keys: string[]): { text: string; added: string[] } {
  const directory = parseObject(directoryText)
  if (directory.items !== undefined && !Array.isArray(directory.items)) {
    throw new Error('directory.json "items" is not a list')
  }
  const items = (directory.items as unknown[] | undefined) ?? []
  const already = new Set<string>()
  placed(items, kind, already)
  const added: string[] = []
  for (const key of keys) {
    if (already.has(key) || added.includes(key)) continue
    items.push({ [kind]: key })
    added.push(key)
  }
  directory.items = items
  return { text: added.length ? formatJson(directory) : directoryText, added }
}
