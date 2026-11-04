/**
 * What the key or value at a position is: its field's description, and for a name that refers to
 * another entry, where that entry is defined. Shown as a hover tooltip, and in the bar under the
 * editor for the cursor (phones have no hover).
 */

import { analyze, type JsonContext, type Path } from './jsonContext'
import { COMMENT_DOC, FILE_SCHEMAS, isComment, objectsOf, optionsOf, schemaAt, typeName, type Schema } from './schema'
import { findEmulator, indexOf, systemForGame, type ConfigData, type ConfigIndex } from './configData'

export interface Description {
  from: number
  to: number
  path: Path
  title: string
  /** Paragraphs; `code` is marked with backticks. */
  body: string[]
}

export function formatPath(path: Path): string {
  return path.map(seg => (typeof seg === 'number' ? `[${seg}]` : seg)).join(' › ')
}

function contextAt(text: string, pos: number): JsonContext {
  const ctx = analyze(text, pos)
  if (ctx.token || text[pos] !== '"') return ctx
  // On a string's opening quote: describe that string.
  return analyze(text, pos + 1)
}

function directorySystems(data: ConfigData): Set<string> {
  const out = new Set<string>()
  const walk = (items: unknown) => {
    if (!Array.isArray(items)) return
    for (const item of items) {
      if (!item || typeof item !== 'object') continue
      const o = item as Record<string, unknown>
      if (typeof o.system === 'string') out.add(o.system)
      walk(o.items)
    }
  }
  walk((data.files['directory.json'] as Record<string, unknown> | undefined)?.items)
  return out
}

function entryFacts(file: string, key: string, data: ConfigData, index: ConfigIndex): string[] {
  const out: string[] = []
  if (file === 'emulators.json') {
    const users = [...index.systems.values()].filter(s => s.choices.some(c => c.emulator === key)).map(s => s.key)
    out.push(users.length ? `Used by ${users.join(', ')} in systems.json.` : 'No system in systems.json uses it yet.')
    const pkg = index.emulators.get(key)?.package
    if (pkg && index.hasHints) out.push(index.apps.has(pkg) ? `${pkg} is installed (${index.apps.get(pkg)}).` : `${pkg} isn't installed on the device.`)
    if (!index.templateEmulators.has(key)) out.push('Not one of the template emulators.')
  } else if (file === 'systems.json') {
    out.push(directorySystems(data).has(key) ? 'Placed in directory.json.' : 'Not placed in directory.json, so it shows under Not in the menu.')
    const tpl = index.templateSystems.get(key)
    if (tpl) out.push(`systems.template.json has it as ${tpl.name}.`)
  } else if (file === 'shortcuts.json') {
    const pkg = index.shortcuts.get(key)?.package
    if (pkg && index.hasHints && !index.apps.has(pkg)) out.push(`${pkg} isn't installed on the device.`)
  } else if (file === 'overrides.json') {
    const sys = systemForGame(index, key)
    out.push(sys ? `A game of ${sys.key} (${sys.name}).` : "No system's ROM folder holds it.")
  }
  return out
}

function referenceFacts(schema: Schema | null, value: string, path: Path, ctx: JsonContext, index: ConfigIndex): string[] {
  const out: string[] = []
  for (const s of optionsOf(schema)) {
    if (s.type !== 'string') continue
    const choice = (s.enum ?? s.suggest ?? []).find(c => c.value === value)
    if (choice?.doc) out.push(`\`${value}\`: ${choice.doc}`)
    switch (s.hint) {
      case 'emulator': {
        const found = findEmulator(index, value)
        if (!found) out.push(`No emulator \`${value}\` in emulators.json or the template.`)
        else if (found.source === 'file') out.push(`${found.info.name}${found.info.package ? ` (${found.info.package})` : ''}, defined in emulators.json.`)
        else out.push(`${found.info.name}: only in emulators.template.json. Copy its entry into emulators.json, or this item is skipped.`)
        if (found?.info.params.length) out.push(`Uses ${found.info.params.map(p => `{${p}}`).join(', ')}.`)
        break
      }
      case 'system': {
        const sys = index.systems.get(value)
        out.push(sys ? `${sys.name}, defined in systems.json (ROM folder ${sys.roms}).` : `No system \`${value}\` in systems.json.`)
        break
      }
      case 'shortcut': {
        const sc = index.shortcuts.get(value)
        out.push(sc ? `${sc.name}, defined in shortcuts.json.` : `No shortcut \`${value}\` in shortcuts.json.`)
        break
      }
      case 'package':
        if (index.hasHints) out.push(index.apps.has(value) ? `Installed: ${index.apps.get(value)}.` : "Not installed on the device (or it has no home-screen icon).")
        break
      case 'core': {
        const users = index.templateCores.get(value)
        if (users) out.push(`The templates use it for ${users.join(', ')}.`)
        const emulator = ctx.siblingValues.emulator
        const pkg = typeof emulator === 'string' ? findEmulator(index, emulator)?.info.package : undefined
        const device = pkg ? index.deviceCores.get(pkg) : undefined
        if (device) out.push(device.includes(value) ? `${pkg} has it installed.` : `${pkg} doesn't report having it.`)
        break
      }
      case 'emulatorChoice': {
        const game = path[path.length - 1]
        const sys = typeof game === 'string' ? systemForGame(index, game) : null
        const c = sys?.choices.find(x => x.name === value)
        if (sys && c) out.push(`${sys.key}'s ${c.emulator}${c.core ? ` with ${c.core}` : ''}${sys.choices[0] === c ? ', its default' : ''}.`)
        break
      }
      case 'romm': {
        const users = index.rommSlugs.get(value.toLowerCase())
        if (users) out.push(`RomM slug the templates use for ${users.join(', ')}.`)
        break
      }
    }
  }
  return out
}

/** The description at [pos], or null where there is nothing to say. */
export function describe(file: string, text: string, pos: number, data: ConfigData): Description | null {
  const root = FILE_SCHEMAS[file]
  if (!root) return null
  const ctx = contextAt(text, pos)
  const tok = ctx.token
  if (!tok || tok.type !== 'string' || ctx.kind === 'none') return null
  const index = indexOf(data)
  const realFile = file.replace('.template', '')

  if (ctx.kind === 'key') {
    const key = tok.text
    const path = [...ctx.path, key]
    if (isComment(key)) return { from: tok.from, to: tok.to, path, title: key, body: [COMMENT_DOC] }
    const objects = objectsOf(schemaAt(root, ctx.path))
    for (const obj of objects) {
      const own = obj.props?.[key]
      const schema = own ?? obj.additional
      if (!schema) continue
      const body = [own ? own.doc ?? '' : obj.keyDoc ?? '', ...(own ? [] : [schema.doc ?? ''])].filter(Boolean)
      if (ctx.path.length === 0 || (realFile === 'overrides.json' && ctx.path.length === 1)) body.push(...entryFacts(realFile, key, data, index))
      const required = obj.required?.includes(key) ? ' · required' : ''
      return { from: tok.from, to: tok.to, path, title: `${key} — ${typeName(schema)}${required}`, body }
    }
    return { from: tok.from, to: tok.to, path, title: key, body: ["Cannoli doesn't read this key here."] }
  }

  const schema = schemaAt(root, ctx.path)
  if (!schema) return null
  const last = ctx.path[ctx.path.length - 1]
  const name = typeof last === 'number' ? formatPath(ctx.path.slice(-2)) : String(last ?? '')
  const body = [schema.doc ?? '', ...referenceFacts(schema, tok.text, ctx.path, ctx, index)].filter(Boolean)
  if (!body.length) return null
  return { from: tok.from, to: tok.to, path: ctx.path, title: `${name} — ${typeName(schema)}`, body }
}
