/**
 * Inline checks for a config file: where its JSON breaks, then (for valid JSON) what the app's
 * parsers would skip or ignore, and names that refer to nothing in the other files. The server's
 * problems list stays the final word; these are found as you type.
 */

import { findJsonError } from '@/lib/configMerge'
import { parseTree, type Path, type PNode } from './jsonContext'
import {
  BUILT_IN_NAMES,
  FILE_SCHEMAS,
  ROM_EXTRA_KINDS,
  isComment,
  typeName,
  type ObjectSchema,
  type Schema,
  type StringSchema,
} from './schema'
import { choiceName, findEmulator, indexOf, systemForGame, type ConfigData, type ConfigIndex } from './configData'

export interface Issue {
  from: number
  to: number
  severity: 'error' | 'warning' | 'info'
  message: string
}

const NODE_NAMES: Record<PNode['type'], string> = {
  object: 'an object', array: 'a list', string: 'text', number: 'a number', boolean: 'true/false', null: 'null',
}

function fits(schema: Schema, node: PNode): boolean {
  switch (schema.type) {
    case 'anyOf': return schema.anyOf.some(s => fits(s, node))
    case 'integer': return node.type === 'number' && Number.isInteger(node.value)
    case 'number': return node.type === 'number'
    default: return schema.type === node.type
  }
}

/** Edit distance, for "did you mean". */
function distance(a: string, b: string): number {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array<number>(b.length).fill(0)])
  for (let j = 1; j <= b.length; j++) d[0]![j] = j
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      d[i]![j] = Math.min(d[i - 1]![j]! + 1, d[i]![j - 1]! + 1, d[i - 1]![j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1))
    }
  }
  return d[a.length]![b.length]!
}

function closest(key: string, candidates: string[]): string | undefined {
  let best: string | undefined
  let bestD = 3
  for (const c of candidates) {
    const dd = distance(key.toLowerCase(), c.toLowerCase())
    if (dd < bestD) {
      best = c
      bestD = dd
    }
  }
  return best
}

const PLACEHOLDER = /\{([A-Za-z0-9_]+)\}/g

class Checker {
  issues: Issue[] = []
  placed = new Set<string>()
  readonly file: string
  readonly index: ConfigIndex | null
  constructor(file: string, index: ConfigIndex | null) {
    this.file = file
    this.index = index
  }

  add(from: number, to: number, severity: Issue['severity'], message: string) {
    this.issues.push({ from, to: Math.max(to, from + 1), severity, message })
  }

  /** [at] is the key leading to [node], where problems with the whole value are shown. */
  walk(node: PNode, schema: Schema | null, path: Path, at: { from: number; to: number }) {
    if (!schema) return
    if (schema.type === 'anyOf') {
      const option = schema.anyOf.find(s => fits(s, node))
      if (!option) {
        this.add(node.from, node.to, 'error', `Expected ${typeName(schema)}, not ${NODE_NAMES[node.type]}.`)
        return
      }
      this.walk(node, option, path, at)
      return
    }
    if (!fits(schema, node)) {
      const want = schema.type === 'integer' ? 'a whole number' : typeName(schema)
      this.add(node.from, node.to, 'error', `Expected ${want}, not ${NODE_NAMES[node.type]}.`)
      return
    }
    if (node.type === 'string' && schema.type === 'string') this.string(node, schema, path)
    else if (node.type === 'array' && schema.type === 'array') {
      node.items.forEach((item, i) => this.walk(item, schema.items, [...path, i], item))
    } else if (node.type === 'object' && schema.type === 'object') this.object(node, schema, path, at)
  }

  string(node: PNode & { type: 'string' }, schema: StringSchema, path: Path) {
    if (schema.enum && !schema.enum.some(c => c.value === node.value)) {
      const values = schema.enum.map(c => c.value).join(', ')
      const allowedElsewhere = this.file === 'shortcuts.json' && ROM_EXTRA_KINDS.has(node.value)
      this.add(node.from, node.to, 'error', allowedElsewhere
        ? `A shortcut takes no game, so "${node.value}" doesn't apply. Use one of: ${values}.`
        : `"${node.value}" isn't one of: ${values}.`)
      return
    }
    if (this.index && schema.hint) this.reference(node, schema.hint, path)
  }

  reference(node: PNode & { type: 'string' }, hint: StringSchema['hint'], path: Path) {
    const index = this.index!
    const v = node.value.trim()
    if (!v) return
    switch (hint) {
      case 'emulator':
        if (!index.emulators.has(v)) {
          this.add(node.from, node.to, 'warning', index.templateEmulators.has(v)
            ? `No emulator "${v}" in emulators.json, so this item is skipped. emulators.template.json has it: copy its entry over.`
            : `No emulator "${v}" in emulators.json, so this item is skipped.`)
        }
        break
      case 'system':
        if (!index.systems.has(v)) {
          this.add(node.from, node.to, 'warning', index.templateSystems.has(v)
            ? `No system "${v}" in systems.json, so this item is skipped. systems.template.json has it.`
            : `No system "${v}" in systems.json, so this item is skipped.`)
        }
        break
      case 'shortcut':
        if (!index.shortcuts.has(v)) this.add(node.from, node.to, 'warning', `No shortcut "${v}" in shortcuts.json, so this item is skipped.`)
        break
      case 'package':
        if (index.hasHints && index.apps.size && !index.apps.has(v)) {
          this.add(node.from, node.to, 'info', `${v} isn't installed on the device (or has no home-screen icon).`)
        }
        break
      case 'romFolder':
        if (index.hasHints && !v.startsWith('/') && !index.romFolders.some(f => f.toLowerCase() === v.split('/')[0]!.toLowerCase())) {
          this.add(node.from, node.to, 'info', `There's no folder Roms/${v} on the device yet.`)
        } else if (v.split('/').includes('..')) {
          this.add(node.from, node.to, 'error', '"roms" can\'t contain "..": this system is skipped.')
        }
        break
      case 'emulatorChoice': {
        const game = path[path.length - 1]
        const sys = typeof game === 'string' ? systemForGame(index, game) : null
        if (sys && !sys.choices.some(c => c.name === v)) {
          this.add(node.from, node.to, 'warning', `"${v}" isn't one of ${sys.key}'s emulators (${sys.choices.map(c => c.name).join(', ') || 'none'}), so the game uses the default.`)
        }
        break
      }
    }
  }

  object(node: PNode & { type: 'object' }, schema: ObjectSchema, path: Path, at: { from: number; to: number }) {
    const seen = new Set<string>()
    for (const p of node.props) {
      if (seen.has(p.key)) this.add(p.keyFrom, p.keyTo, 'warning', `"${p.key}" is here twice: the last one is used.`)
      seen.add(p.key)
      if (isComment(p.key)) continue
      const child = schema.props?.[p.key] ?? schema.additional
      if (!child) {
        const guess = closest(p.key, Object.keys(schema.props ?? {}))
        this.add(p.keyFrom, p.keyTo, 'warning', `Cannoli doesn't read "${p.key}" here${guess ? `: did you mean "${guess}"?` : '.'} Keys starting with _ are comments.`)
        continue
      }
      if (!p.key.trim() && schema.additional) {
        this.add(p.keyFrom, p.keyTo, 'error', 'An entry needs a name: this one is skipped.')
        continue
      }
      this.walk(p.value, child, [...path, p.key], { from: p.keyFrom, to: p.keyTo })
    }
    for (const req of schema.required ?? []) {
      if (!seen.has(req)) this.add(at.from, at.to, 'error', `Needs "${req}"${schema.title ? `: this ${schema.title} is skipped` : ''}.`)
    }
    if (schema.rule) this.rule(schema.rule, node, at)
  }

  rule(rule: ObjectSchema['rule'], node: PNode & { type: 'object' }, at: { from: number; to: number }) {
    const prop = (k: string) => node.props.find(p => p.key === k)
    const text = (k: string) => {
      const v = prop(k)?.value
      return v?.type === 'string' ? v.value : v?.type === 'number' || v?.type === 'boolean' ? String(v.value) : undefined
    }
    switch (rule) {
      case 'extra':
      case 'shortcutExtra': {
        const kind = text('kind')
        if ((kind === 'string' || kind === 'int' || kind === 'bool') && !text('value')) {
          this.add(at.from, at.to, 'error', `An extra of kind ${kind} needs a "value": it is skipped.`)
        }
        if (kind === 'string_array' && prop('values')?.value.type !== 'array') {
          this.add(at.from, at.to, 'error', 'An extra of kind string_array needs "values", a list: it is skipped.')
        }
        if (kind && ROM_EXTRA_KINDS.has(kind)) {
          for (const k of ['value', 'values']) {
            const p = prop(k)
            if (p) this.add(p.keyFrom, p.keyTo, 'warning', `Kind ${kind} passes the game: "${k}" is ignored.`)
          }
        }
        break
      }
      case 'data':
      case 'shortcutData': {
        const kind = text('kind')
        if (kind === 'custom_scheme') {
          for (const k of ['scheme', 'authority']) if (!text(k)) this.add(at.from, at.to, 'error', `custom_scheme needs "${k}".`)
        }
        const grant = prop('grantPermission')
        if (grant && kind !== 'file_provider') this.add(grant.keyFrom, grant.keyTo, 'warning', 'Only file_provider uses "grantPermission".')
        break
      }
      case 'shortcut': {
        const extras = prop('extras')?.value
        if (extras?.type !== 'array') break
        for (const x of extras.items) {
          if (x.type !== 'object') continue
          for (const p of x.props) {
            if (p.key !== 'value' && p.key !== 'values') continue
            const strings = p.value.type === 'array' ? p.value.items : [p.value]
            for (const s of strings) {
              if (s.type !== 'string') continue
              const names = [...s.value.matchAll(PLACEHOLDER)].map(m => m[1]!).filter(n => n !== 'package')
              if (names.length) this.add(s.from, s.to, 'error', `A shortcut takes no game, so nothing fills ${names.map(n => `{${n}}`).join(', ')}: it is skipped.`)
            }
          }
        }
        break
      }
      case 'directoryItem': {
        const kinds = ['folder', 'system', 'shortcut', 'app'].filter(k => prop(k))
        if (kinds.length !== 1) {
          this.add(node.from, node.from + 1, 'error', kinds.length
            ? `Only one of ${kinds.join(', ')} per item: it is skipped.`
            : 'Needs one of "folder", "system", "shortcut" or "app": it is skipped.')
          break
        }
        const kind = kinds[0]!
        const p = prop(kind)!
        if (p.value.type === 'string' && !p.value.value.trim()) this.add(p.value.from, p.value.to, 'error', `"${kind}" needs a name: it is skipped.`)
        if (kind === 'system' && p.value.type === 'string') {
          const name = p.value.value.trim()
          if (this.placed.has(name)) this.add(p.value.from, p.value.to, 'warning', `System "${name}" is already placed: a system goes in one place, so this is skipped.`)
          this.placed.add(name)
        }
        const name = prop('name')
        if (name && kind !== 'app') this.add(name.keyFrom, name.keyTo, 'warning', kind === 'folder' ? 'A folder is named by "folder": "name" is ignored.' : 'Only an app uses "name": it is ignored.')
        const items = prop('items')
        if (items && kind !== 'folder') this.add(items.keyFrom, items.keyTo, 'warning', 'Only a folder has "items": they are ignored.')
        break
      }
      case 'emulatorRef': {
        for (const p of node.props) {
          if (BUILT_IN_NAMES.has(p.key)) this.add(p.keyFrom, p.keyTo, 'warning', `{${p.key}} is filled in at launch: this value is ignored.`)
        }
        const emulator = text('emulator')
        if (emulator) this.missingParams(emulator, new Set(node.props.map(p => p.key)), at)
        break
      }
      case 'system': {
        const ext = prop('extensions')
        if (ext?.value.type === 'array' && ext.value.items.length === 0) {
          this.add(ext.keyFrom, ext.keyTo, 'warning', 'An empty list would hide every game, so it is ignored: leave "extensions" out to accept every file.')
        }
        const list = prop('emulators')?.value
        if (list?.type !== 'array') break
        const names = new Set<string>()
        for (const item of list.items) {
          const plainItem = item.type === 'string' ? item.value : item.type === 'object'
            ? Object.fromEntries(item.props.map(p => [p.key, p.value.type === 'string' ? p.value.value : undefined]))
            : undefined
          const name = choiceName(plainItem)
          if (name) {
            if (names.has(name)) this.add(item.from, item.to, 'error', `"${name}" is already listed: give one a "name". This one is skipped.`)
            names.add(name)
          }
          if (item.type === 'string' && item.value.trim()) this.missingParams(item.value.trim(), new Set(), item)
        }
        break
      }
    }
  }

  /** A system item that leaves out a placeholder its emulator uses is skipped. */
  missingParams(emulator: string, given: Set<string>, at: { from: number; to: number }) {
    const emu = this.index && findEmulator(this.index, emulator)
    if (!emu || emu.source !== 'file') return
    const missing = emu.info.params.filter(p => !given.has(p))
    if (missing.length) {
      this.add(at.from, at.to, 'warning', `${emulator} uses ${missing.map(p => `{${p}}`).join(', ')}: give ${missing.length > 1 ? 'them values' : 'it a value'} with ${missing.map(p => `"${p}"`).join(', ')}, or this item is skipped.`)
    }
  }

  overrides(root: PNode) {
    if (!this.index || root.type !== 'object') return
    const games = root.props.find(p => p.key === 'games')?.value
    if (games?.type !== 'object' || !this.index.systems.size) return
    for (const p of games.props) {
      if (isComment(p.key)) continue
      if (!systemForGame(this.index, p.key)) {
        this.add(p.keyFrom, p.keyTo, 'warning', "No system's ROM folder holds this game: start the path with a system's folder, such as \"GBA/\".")
      }
    }
  }
}

/** The problems in [file]'s [text]. Templates get the JSON check only. */
export function validate(file: string, text: string, data: ConfigData | null): Issue[] {
  const err = findJsonError(text)
  if (err) {
    const from = Math.min(err.offset, Math.max(0, text.length - 1))
    return [{ from, to: Math.min(text.length, from + 1), severity: 'error', message: `Not valid JSON: ${err.reason}.` }]
  }
  const schema = FILE_SCHEMAS[file]
  if (!schema || file.includes('.template.')) return []
  const tree = parseTree(text)
  if (!tree) return []
  const checker = new Checker(file, data ? indexOf(data) : null)
  checker.walk(tree, schema, [], { from: tree.from, to: tree.from + 1 })
  if (file === 'overrides.json') checker.overrides(tree)
  return checker.issues.sort((a, b) => a.from - b.from)
}
