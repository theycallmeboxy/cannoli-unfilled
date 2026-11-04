/**
 * Completions for the config editor, worked out from the text, the cursor and what the other
 * files, the templates and the device hold. Pure functions: the CodeMirror glue in editorSetup.ts
 * turns a [SuggestResult] into its completion list.
 */

import { analyze, indentAt, type JsonContext, type Path } from './jsonContext'
import {
  ACTIONS,
  BUILT_IN_PLACEHOLDERS,
  COMMENT_DOC,
  FILE_SCHEMAS,
  objectsOf,
  optionsOf,
  schemaAt,
  typeName,
  type Choice,
  type Hint,
  type KeyHint,
  type ObjectSchema,
  type Schema,
} from './schema'
import { findEmulator, indexOf, systemForGame, type ConfigData, type ConfigIndex } from './configData'

export interface Suggestion {
  label: string
  detail?: string
  info?: string
  type?: 'property' | 'keyword' | 'variable' | 'constant' | 'class' | 'text' | 'enum' | 'function' | 'namespace'
  boost?: number
  section?: string
  /** What replaces the result's range. */
  insert: string
  /** Where the cursor goes in [insert]; the end when left out. */
  cursor?: number
  /** Open the list again after inserting, for the value just begun. */
  reopen?: boolean
}

export interface SuggestResult {
  /** Where the typed text the list filters on starts. */
  filterFrom: number
  /** The range an option replaces. */
  from: number
  to: number
  /** A comma to add here for the item before, which lacks one. */
  commaBefore: number | null
  /** Add a comma after the insert: something follows it. */
  commaAfter: boolean
  options: Suggestion[]
}

const CURSOR = '¦'

/** [text] with its cursor mark taken out, and where it was. */
export function withCursor(text: string): { insert: string; cursor?: number } {
  const at = text.indexOf(CURSOR)
  return at < 0 ? { insert: text } : { insert: text.slice(0, at) + text.slice(at + 1), cursor: at }
}

function option(label: string, text: string, rest: Omit<Suggestion, 'label' | 'insert' | 'cursor'> = {}): Suggestion {
  return { label, ...withCursor(text), ...rest }
}

/** [value] as JSON, with lines after the first indented by [indent]. */
export function pretty(value: unknown, indent: string): string {
  return JSON.stringify(value, null, 2).replace(/\n/g, `\n${indent}`)
}

interface Env {
  file: string
  text: string
  ctx: JsonContext
  index: ConfigIndex
  indent: string
}

/**
 * The completions at [pos] in [file]'s [text], or null where there are none. Without [explicit]
 * (the list opening as someone types), a place with nothing typed yet offers nothing.
 */
export function suggest(file: string, text: string, pos: number, data: ConfigData, explicit = true): SuggestResult | null {
  const root = FILE_SCHEMAS[file]
  if (!root) return null
  const ctx = analyze(text, pos)
  const env: Env = { file, text, ctx, index: indexOf(data), indent: indentAt(text, pos) }

  if (ctx.kind === 'none') {
    if (text.trim() === '' && explicit) return skeleton(file, pos)
    return null
  }
  if (!explicit && (!ctx.token || ctx.token.type === 'number')) return null

  const base = {
    filterFrom: ctx.token ? ctx.token.from + (ctx.token.type === 'string' ? 1 : 0) : pos,
    from: ctx.from,
    to: ctx.to,
    commaBefore: ctx.commaBefore,
    commaAfter: ctx.commaAfter,
  }

  if (ctx.kind === 'key') {
    const options = keyOptions(env, objectsOf(schemaAt(root, ctx.path)))
    return options.length ? { ...base, options } : null
  }

  const schema = schemaAt(root, ctx.path)
  const placeholders = placeholderResult(env, schema, pos)
  if (placeholders) return placeholders
  const options = valueOptions(env, schema)
  if (ctx.inArray && !ctx.token) {
    const list = schemaAt(root, ctx.path.slice(0, -1))
    if (list?.type === 'array') {
      for (const s of list.snippets ?? []) options.push(option(s.label, s.text, { info: s.doc, type: 'namespace', reopen: true, boost: -1 }))
    }
  }
  return options.length ? { ...base, options: dedupe(options) } : null
}

function dedupe(options: Suggestion[]): Suggestion[] {
  const seen = new Set<string>()
  return options.filter(o => {
    const key = `${o.label}\u0000${o.insert}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function skeleton(file: string, pos: number): SuggestResult {
  const text = file === 'directory.json'
    ? '{\n  "items": [\n    ¦\n  ]\n}\n'
    : file === 'overrides.json'
      ? '{\n  "games": {\n    ¦\n  }\n}\n'
      : '{\n  ¦\n}\n'
  return {
    filterFrom: pos,
    from: pos,
    to: pos,
    commaBefore: null,
    commaAfter: false,
    options: [option('{ … }', text, { detail: `an empty ${file}`, type: 'keyword', reopen: true })],
  }
}

// --- Keys ---

function keyOptions(env: Env, objects: ObjectSchema[]): Suggestion[] {
  const { ctx } = env
  const taken = new Set(ctx.siblings)
  const out: Suggestion[] = []
  for (const obj of objects) {
    for (const [key, schema] of Object.entries(obj.props ?? {})) {
      if (taken.has(key) || !keyApplies(obj, key, env)) continue
      const required = obj.required?.includes(key)
      out.push(keyOption(env, key, schema, {
        detail: required ? `${typeName(schema)} · required` : typeName(schema),
        info: schema.doc,
        type: 'property',
        boost: required ? 3 : 1,
      }))
    }
    if (obj.keyHint) out.push(...keyHintOptions(env, obj.keyHint, obj.additional ?? null))
  }
  if (objects.length && !taken.has('_note')) {
    out.push(keyOption(env, '_note', { type: 'string' }, { detail: 'comment', info: COMMENT_DOC, type: 'text', boost: -5 }))
  }
  return dedupe(out)
}

const ITEM_KINDS = ['folder', 'system', 'shortcut', 'app']

/** Whether [key] makes sense next to what the object already has. */
function keyApplies(obj: ObjectSchema, key: string, env: Env): boolean {
  const { ctx } = env
  const has = (k: string) => ctx.siblings.includes(k)
  const kind = ctx.siblingValues.kind
  switch (obj.rule) {
    case 'directoryItem': {
      const present = ITEM_KINDS.filter(has)
      if (ITEM_KINDS.includes(key)) return present.length === 0
      if (key === 'name') return present.length === 0 || has('app')
      if (key === 'items') return present.length === 0 || has('folder')
      return true
    }
    case 'data':
    case 'shortcutData':
      if (key === 'grantPermission') return kind === undefined || kind === 'file_provider'
      if (key === 'scheme' || key === 'authority') return kind === undefined || kind === 'custom_scheme'
      return true
    case 'extra':
    case 'shortcutExtra':
      if (key === 'value') return kind === undefined || kind === 'string' || kind === 'int' || kind === 'bool'
      if (key === 'values') return kind === undefined || kind === 'string_array'
      return true
    case 'emulatorRef':
      // `core` only for an emulator that uses {core}, when that is known.
      if (key === 'core' && typeof ctx.siblingValues.emulator === 'string') {
        const emu = findEmulator(env.index, ctx.siblingValues.emulator)
        return !emu || emu.info.params.includes('core')
      }
      return true
    default:
      return true
  }
}
function keyOption(env: Env, key: string, schema: Schema | null, rest: Omit<Suggestion, 'label' | 'insert' | 'cursor'>): Suggestion {
  if (env.ctx.hasColon) return { label: key, insert: JSON.stringify(key), ...rest }
  const value = valueTemplate(schema, env.indent)
  return option(key, `${JSON.stringify(key)}: ${value.text}`, { ...rest, reopen: value.reopen })
}

/** What a new key's value starts as, with the cursor in it. */
function valueTemplate(schema: Schema | null, indent: string): { text: string; reopen: boolean } {
  if (!schema) return { text: `"${CURSOR}"`, reopen: false }
  switch (schema.type) {
    case 'anyOf':
      return valueTemplate(schema.anyOf[0] ?? null, indent)
    case 'string':
      return { text: `"${CURSOR}"`, reopen: !!(schema.enum || schema.suggest || schema.hint) }
    case 'integer':
    case 'number':
      return { text: CURSOR, reopen: !!schema.suggest }
    case 'boolean':
      return { text: CURSOR, reopen: true }
    case 'null':
      return { text: 'null', reopen: false }
    case 'array':
      return { text: `[${CURSOR}]`, reopen: true }
    case 'object': {
      const req = schema.required ?? []
      const first = req.length === 1 ? schema.props?.[req[0]!] : undefined
      if (first?.type === 'string') return { text: `{ ${JSON.stringify(req[0])}: "${CURSOR}" }`, reopen: true }
      return { text: `{\n${indent}  ${CURSOR}\n${indent}}`, reopen: true }
    }
  }
}

function installedNote(index: ConfigIndex, pkg: string | undefined): string {
  if (!pkg || !index.hasHints) return ''
  return index.apps.has(pkg) ? ' · installed' : ''
}

function keyHintOptions(env: Env, hint: KeyHint, valueSchema: Schema | null): Suggestion[] {
  const { index, ctx, indent } = env
  const taken = new Set(ctx.siblings)
  const entry = (key: string, value: unknown, rest: Omit<Suggestion, 'label' | 'insert' | 'cursor'>): Suggestion =>
    ctx.hasColon
      ? { label: key, insert: JSON.stringify(key), ...rest }
      : { label: key, insert: `${JSON.stringify(key)}: ${pretty(value, indent)}`, ...rest }
  switch (hint) {
    case 'newEmulator':
      return [...index.templateEmulators.values()].filter(e => !taken.has(e.key)).map(e => {
        const installed = !!e.package && index.apps.has(e.package)
        return entry(e.key, e.entry, {
          detail: `${e.name}${installedNote(index, e.package)}`,
          info: [e.note, e.package && `Package ${e.package}.`, 'Inserts the whole entry from emulators.template.json.'].filter(Boolean).join('\n'),
          type: 'class',
          section: 'emulators.template.json',
          boost: installed ? 2 : 0,
        })
      })
    case 'newSystem':
      return [...index.templateSystems.values()].filter(s => !taken.has(s.key)).map(s => {
        const folder = index.romFolders.find(f => f.toLowerCase() === s.roms.toLowerCase())
        const missing = [...new Set(s.choices.map(c => c.emulator))].filter(e => !index.emulators.has(e))
        return entry(s.key, s.entry, {
          detail: `${s.name}${folder ? ` · Roms/${folder} found` : ''}`,
          info: [
            s.note,
            `Emulators: ${s.choices.map(c => (c.core ? `${c.emulator} (${c.core})` : c.emulator)).join(', ') || 'none'}.`,
            missing.length ? `Not in emulators.json yet: ${missing.join(', ')}.` : '',
            'Inserts the whole entry from systems.template.json.',
          ].filter(Boolean).join('\n'),
          type: 'class',
          section: 'systems.template.json',
          boost: folder ? 2 : 0,
        })
      })
    case 'newShortcut':
      return [...index.templateShortcuts.values()].filter(s => !taken.has(s.key)).map(s =>
        entry(s.key, s.entry, {
          detail: `${s.name}${installedNote(index, s.package)}`,
          info: [s.note, 'Inserts the whole entry from shortcuts.template.json.'].filter(Boolean).join('\n'),
          type: 'class',
          section: 'shortcuts.template.json',
        }),
      )
    case 'game':
      return index.games.filter(g => !taken.has(g)).map(g => {
        const sys = systemForGame(index, g)
        return keyOption(env, g, valueSchema, { detail: sys ? sys.key : 'no system', type: 'variable', boost: sys ? 0 : -2 })
      })
    case 'param': {
      const emulator = ctx.siblingValues.emulator
      if (typeof emulator !== 'string') return []
      const emu = findEmulator(index, emulator)
      return (emu?.info.params ?? []).filter(p => !taken.has(p)).map(p =>
        keyOption(env, p, valueSchema, {
          detail: `fills {${p}}`,
          info: `${emulator} uses {${p}} in its extras: this value fills it.`,
          type: 'property',
          boost: 4,
        }),
      )
    }
  }
}

// --- Values ---

function choiceOption(c: Choice, rest: Omit<Suggestion, 'label' | 'insert' | 'cursor'> = {}): Suggestion {
  const insert = JSON.stringify(c.value)
  return { label: typeof c.value === 'string' ? c.value : String(c.value), insert, info: c.doc, ...rest }
}

function valueOptions(env: Env, schema: Schema | null): Suggestion[] {
  const tok = env.ctx.token
  const out: Suggestion[] = []
  for (const s of optionsOf(schema)) {
    if (tok?.type === 'string' && s.type !== 'string') continue
    switch (s.type) {
      case 'string':
        for (const c of s.enum ?? []) out.push(choiceOption(c, { type: 'enum', boost: 2 }))
        for (const c of s.suggest ?? []) out.push(choiceOption(c, { type: 'constant' }))
        if (s.hint) out.push(...hintOptions(env, s.hint))
        break
      case 'boolean':
        out.push(choiceOption({ value: true }, { type: 'keyword' }), choiceOption({ value: false }, { type: 'keyword' }))
        break
      case 'null':
        out.push(choiceOption({ value: null, doc: s.doc }, { type: 'keyword', boost: -1 }))
        break
      case 'integer':
      case 'number':
        for (const c of s.suggest ?? []) out.push(choiceOption(c, { type: 'constant', detail: c.doc }))
        break
      case 'array':
        if (!tok) out.push(option('[ … ]', `[${CURSOR}]`, { detail: 'list', type: 'keyword', reopen: true, boost: -2 }))
        break
      case 'object':
        if (!tok && !env.ctx.inArray) {
          const t = valueTemplate(s, env.indent)
          out.push(option('{ … }', t.text, { detail: typeName(s), type: 'keyword', reopen: t.reopen, boost: -2 }))
        }
        break
    }
  }
  return out
}

function systemKeyOf(env: Env, path: Path): string | undefined {
  return env.file === 'systems.json' && typeof path[0] === 'string' ? path[0] : undefined
}

/** The package of the entry the cursor is in: from the text around it, or else from the saved files. */
function entryPackage(env: Env): string | undefined {
  const own = env.ctx.siblingValues.package
  if (typeof own === 'string') return own
  const key = env.ctx.path[0]
  if (typeof key !== 'string') return undefined
  const { index } = env
  const found = env.file === 'shortcuts.json'
    ? index.shortcuts.get(key) ?? index.templateShortcuts.get(key)
    : index.emulators.get(key) ?? index.templateEmulators.get(key)
  return found?.package
}

function listed(env: Env): Set<string> {
  return new Set(env.ctx.arrayItems.filter((v): v is string => typeof v === 'string').map(v => v.toLowerCase()))
}

function placedSystems(env: Env): Set<string> {
  const out = new Set<string>()
  const re = /"system"\s*:\s*"((?:[^"\\]|\\.)*)"/g
  for (const m of env.text.matchAll(re)) {
    const at = m.index ?? 0
    if (env.ctx.token && at <= env.ctx.token.from && env.ctx.token.from < at + m[0].length) continue
    out.add(m[1]!)
  }
  return out
}

function hintOptions(env: Env, hint: Hint): Suggestion[] {
  const { index, ctx } = env
  const path = ctx.path
  const last = path[path.length - 1]
  switch (hint) {
    case 'emulator': {
      const sysKey = systemKeyOf(env, path)
      const wanted = new Set(index.templateSystems.get(sysKey ?? '')?.choices.map(c => c.emulator) ?? [])
      const already = ctx.inArray ? listed(env) : new Set<string>()
      const out: Suggestion[] = []
      for (const e of index.emulators.values()) {
        if (already.has(e.key.toLowerCase())) continue
        out.push(choiceOption({ value: e.key }, {
          detail: `${e.name}${installedNote(index, e.package)}`,
          info: [e.note, e.package && `Package ${e.package}.`, wanted.has(e.key) && `The template lists it for ${sysKey}.`].filter(Boolean).join('\n'),
          type: 'variable',
          section: 'emulators.json',
          boost: wanted.has(e.key) ? 3 : 1,
        }))
      }
      for (const e of index.templateEmulators.values()) {
        if (index.emulators.has(e.key) || already.has(e.key.toLowerCase())) continue
        out.push(choiceOption({ value: e.key }, {
          detail: `${e.name} · from template${installedNote(index, e.package)}`,
          info: [e.note, `Not in emulators.json yet: add its entry from emulators.template.json, or it is skipped.`].filter(Boolean).join('\n'),
          type: 'variable',
          section: 'emulators.template.json',
          boost: wanted.has(e.key) ? 0 : -2,
        }))
      }
      return out
    }
    case 'core': {
      const emulator = ctx.siblingValues.emulator
      const pkg = typeof emulator === 'string' ? findEmulator(index, emulator)?.info.package : undefined
      const device = pkg ? index.deviceCores.get(pkg) : undefined
      const own = new Set(index.templateSystems.get(systemKeyOf(env, path) ?? '')?.choices.map(c => c.core).filter(Boolean) as string[])
      const cores = new Set([...(device ?? []), ...index.templateCores.keys()])
      return [...cores].map(core => {
        const users = index.templateCores.get(core) ?? []
        const installed = device?.includes(core)
        const state = device ? (installed ? 'installed' : 'not installed') : ''
        return choiceOption({ value: core }, {
          detail: [state, users.length ? users.slice(0, 4).join(', ') + (users.length > 4 ? '…' : '') : ''].filter(Boolean).join(' · '),
          info: [
            users.length ? `The templates use it for ${users.join(', ')}.` : 'Reported by the device.',
            device ? `${pkg} ${installed ? 'has it' : "doesn't report it"}.` : '',
          ].filter(Boolean).join('\n'),
          type: 'constant',
          boost: (own.has(core) ? 3 : 0) + (installed ? 1 : 0),
        })
      })
    }
    case 'romm': {
      const own = new Set(index.templateSystems.get(systemKeyOf(env, path) ?? '')?.romm ?? [])
      const already = listed(env)
      return [...index.rommSlugs].filter(([slug]) => !already.has(slug)).map(([slug, systems]) =>
        choiceOption({ value: slug }, { detail: systems.join(', '), type: 'constant', boost: own.has(slug) ? 3 : 0 }),
      )
    }
    case 'extension': {
      const own = new Set(index.templateSystems.get(systemKeyOf(env, path) ?? '')?.extensions.map(e => e.toLowerCase()) ?? [])
      const already = listed(env)
      return [...index.extensions].filter(([ext]) => !already.has(ext)).map(([ext, systems]) =>
        choiceOption({ value: ext }, {
          detail: own.has(ext) ? `${systemKeyOf(env, path)} template` : systems.slice(0, 4).join(', ') + (systems.length > 4 ? '…' : ''),
          type: 'constant',
          boost: own.has(ext) ? 3 : 0,
        }),
      )
    }
    case 'romFolder': {
      const key = systemKeyOf(env, path) ?? ''
      const tpl = index.templateSystems.get(key)?.roms
      const out = index.romFolders.map(f =>
        choiceOption({ value: f }, {
          detail: 'folder in Roms/',
          type: 'constant',
          boost: f.toLowerCase() === key.toLowerCase() || f === tpl ? 3 : 0,
        }),
      )
      if (tpl && !index.romFolders.includes(tpl)) out.push(choiceOption({ value: tpl }, { detail: 'template · no such folder yet', type: 'constant', boost: 1 }))
      return out
    }
    case 'package': {
      const out: Suggestion[] = []
      const key = typeof path[0] === 'string' ? path[0] : ''
      const tpl = env.file === 'emulators.json'
        ? index.templateEmulators.get(key)
        : env.file === 'shortcuts.json' ? index.templateShortcuts.get(key) : undefined
      if (tpl?.package) {
        out.push(choiceOption({ value: tpl.package }, {
          detail: `${tpl.name} · template${index.hasHints ? (index.apps.has(tpl.package) ? ' · installed' : ' · not installed') : ''}`,
          type: 'constant',
          boost: 5,
        }))
      }
      for (const [pkg, label] of index.apps) {
        out.push(choiceOption({ value: pkg }, { detail: label, type: 'constant', section: 'installed apps' }))
      }
      if (!index.hasHints) {
        for (const e of [...index.templateEmulators.values(), ...index.templateShortcuts.values()]) {
          if (e.package) out.push(choiceOption({ value: e.package }, { detail: `${e.name} · template`, type: 'constant', boost: -1 }))
        }
      }
      return out
    }
    case 'activity': {
      const pkg = entryPackage(env)
      const out: Suggestion[] = []
      for (const [p, activities] of index.activities) {
        for (const a of activities) {
          if (p === pkg) out.push(choiceOption({ value: a }, { detail: 'used for this package', type: 'constant', boost: 3 }))
          else if (a.startsWith(`${pkg ?? '\u0000'}.`)) out.push(choiceOption({ value: a }, { detail: p, type: 'constant' }))
        }
      }
      return out
    }
    case 'action': {
      const pkg = entryPackage(env)
      const builtIn = new Set(ACTIONS.map(c => c.value))
      return [...index.actions].filter(([a]) => !builtIn.has(a)).map(([a, pkgs]) =>
        choiceOption({ value: a }, {
          detail: pkg && pkgs.has(pkg) ? 'used for this package' : [...pkgs].filter(Boolean).slice(0, 2).join(', '),
          type: 'constant',
          boost: pkg && pkgs.has(pkg) ? 3 : -1,
        }),
      )
    }
    case 'extraKey': {
      const pkg = entryPackage(env)
      const own = (pkg && index.extraKeys.get(pkg)) || new Set<string>()
      const all = new Set<string>()
      for (const keys of index.extraKeys.values()) for (const k of keys) all.add(k)
      return [...all].map(k =>
        choiceOption({ value: k }, { detail: own.has(k) ? 'used for this package' : 'from the templates', type: 'constant', boost: own.has(k) ? 3 : -1 }),
      )
    }
    case 'system': {
      const placed = placedSystems(env)
      return [...index.systems.values()].map(s =>
        choiceOption({ value: s.key }, {
          detail: `${s.name}${placed.has(s.key) ? ' · already placed' : ''}`,
          info: placed.has(s.key) ? 'A system goes in one place: a second one is skipped.' : `Defined in systems.json, ROM folder ${s.roms}.`,
          type: 'variable',
          boost: placed.has(s.key) ? -3 : 1,
        }),
      )
    }
    case 'shortcut':
      return [...index.shortcuts.values()].map(s =>
        choiceOption({ value: s.key }, { detail: s.name, info: s.package && `Opens ${s.package}.`, type: 'variable' }),
      )
    case 'emulatorChoice': {
      const game = typeof last === 'string' ? last : ''
      const sys = systemForGame(index, game)
      if (sys) {
        return sys.choices.map((c, i) =>
          choiceOption({ value: c.name }, {
            detail: `${i === 0 ? 'default · ' : ''}${c.emulator}${c.core ? ` · ${c.core}` : ''}`,
            info: i === 0 ? `${sys.key}'s default: an override naming it is the same as none.` : `From ${sys.key}'s emulators.`,
            type: 'variable',
            boost: i === 0 ? -1 : 1,
          }),
        )
      }
      const out: Suggestion[] = []
      for (const s of index.systems.values()) {
        for (const c of s.choices) out.push(choiceOption({ value: c.name }, { detail: s.key, type: 'variable' }))
      }
      return out
    }
    case 'param': {
      const examples = typeof last === 'string' ? index.params.get(last) : undefined
      return [...(examples ?? [])].map(v => choiceOption({ value: v }, { detail: 'used elsewhere', type: 'constant' }))
    }
  }
}

// --- Placeholders ---

function placeholderResult(env: Env, schema: Schema | null, pos: number): SuggestResult | null {
  const tok = env.ctx.token
  if (tok?.type !== 'string') return null
  const mode = optionsOf(schema).find(s => s.type === 'string' && s.placeholders)
  if (!mode || mode.type !== 'string') return null
  const m = /\{([A-Za-z0-9_]*)$/.exec(env.text.slice(tok.from + 1, pos))
  if (!m) return null
  const brace = pos - m[0].length
  const rest = /^[A-Za-z0-9_]*\}?/.exec(env.text.slice(pos))![0]
  const options: Suggestion[] = []
  if (mode.placeholders === 'shortcut') {
    options.push({ label: '{package}', insert: '{package}', info: "The shortcut's package: the only placeholder a shortcut has.", type: 'variable' })
  } else {
    for (const c of BUILT_IN_PLACEHOLDERS) {
      options.push({ label: `{${c.value}}`, insert: `{${c.value}}`, detail: 'built in', info: c.doc, type: 'variable', boost: 1 })
    }
    for (const [name, examples] of env.index.params) {
      options.push({
        label: `{${name}}`,
        insert: `{${name}}`,
        detail: 'from systems',
        info: `Filled from the system's item for this emulator, such as "${name}": "${[...examples][0] ?? ''}".`,
        type: 'variable',
      })
    }
  }
  return { filterFrom: brace, from: brace, to: pos + rest.length, commaBefore: null, commaAfter: false, options }
}
