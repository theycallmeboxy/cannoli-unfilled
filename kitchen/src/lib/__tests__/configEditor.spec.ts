import { describe, expect, it } from 'vitest'
import { analyze, parseTree, plain } from '@/lib/config/jsonContext'
import { FILE_SCHEMAS, schemaAt } from '@/lib/config/schema'
import { suggest, type SuggestResult } from '@/lib/config/suggest'
import { validate } from '@/lib/config/validate'
import { describe as describeAt } from '@/lib/config/describe'
import { indexOf, systemForGame } from '@/lib/config/configData'
import { DATA, at } from './configFixture'

function run(file: string, marked: string, explicit = true): SuggestResult | null {
  const [text, pos] = at(marked)
  return suggest(file, text, pos, DATA, explicit)
}
const labels = (r: SuggestResult | null) => r?.options.map(o => o.label) ?? []
/** The text after applying the option labelled [label], and where the cursor ends up. */
function apply(file: string, marked: string, label: string): string {
  const [text, pos] = at(marked)
  const r = suggest(file, text, pos, DATA)!
  const o = r.options.find(x => x.label === label)!
  expect(o, `option ${label}`).toBeTruthy()
  const insert = o.insert + (r.commaAfter ? ',' : '')
  const cursor = o.cursor ?? o.insert.length
  let out = text.slice(0, r.from) + insert.slice(0, cursor) + '|' + insert.slice(cursor) + text.slice(r.to)
  if (r.commaBefore !== null) out = out.slice(0, r.commaBefore) + ',' + out.slice(r.commaBefore)
  return out
}

describe('analyze', () => {
  it('finds a key being typed, and the keys around it', () => {
    const [text, pos] = at('{ "GBA": { "name": "x", "ro|" , "emulators": [] } }')
    const c = analyze(text, pos)
    expect(c.kind).toBe('key')
    expect(c.path).toEqual(['GBA'])
    expect(c.prefix).toBe('ro')
    expect(c.siblings).toEqual(['name', 'emulators'])
    expect(c.siblingValues.name).toBe('x')
  })

  it('finds values in arrays and objects, with their paths', () => {
    let [text, pos] = at('{ "GBA": { "emulators": [ { "emulator": "retroarch", "core": "mg|" } ] } }')
    let c = analyze(text, pos)
    expect(c.kind).toBe('value')
    expect(c.path).toEqual(['GBA', 'emulators', 0, 'core'])
    expect(c.siblingValues.emulator).toBe('retroarch')
    ;[text, pos] = at('{ "GBA": { "emulators": [ "a", "b", "p| ] } }')
    c = analyze(text, pos)
    expect(c.path).toEqual(['GBA', 'emulators', 2])
    expect(c.inArray).toBe(true)
    expect(c.arrayItems).toEqual(['a', 'b'])
  })

  it('copes with unclosed strings and missing commas', () => {
    let [text, pos] = at('{ "GBA": { "roms": "G|\n  }\n}')
    let c = analyze(text, pos)
    expect(c.kind).toBe('value')
    expect(c.path).toEqual(['GBA', 'roms'])
    expect(c.to).toBe(pos)
    ;[text, pos] = at('{ "a": 1\n  |\n}')
    c = analyze(text, pos)
    expect(c.kind).toBe('key')
    expect(c.commaBefore).toBe(text.indexOf('1') + 1)
  })

  it('parses valid JSON with positions', () => {
    const text = '{ "a": [1, true, null, "x"], "b": { "c": "d" } }'
    const tree = parseTree(text)!
    expect(plain(tree)).toEqual(JSON.parse(text))
    expect(tree.type === 'object' && text.slice(tree.props[1]!.keyFrom, tree.props[1]!.keyTo)).toBe('"b"')
  })
})

describe('schemas', () => {
  it('resolve paths through maps, lists and either-or values', () => {
    const systems = FILE_SCHEMAS['systems.json']!
    expect(schemaAt(systems, ['GBA', 'emulators', 0, 'core'])).toMatchObject({ type: 'string', hint: 'core' })
    expect(schemaAt(systems, ['GBA', 'emulators', 1])).toMatchObject({ type: 'anyOf' })
    expect(schemaAt(systems, ['GBA', 'romm', 0])).toMatchObject({ hint: 'romm' })
    expect(schemaAt(FILE_SCHEMAS['directory.json']!, ['items', 0, 'items', 3, 'system'])).toMatchObject({ hint: 'system' })
    expect(schemaAt(FILE_SCHEMAS['emulators.json']!, ['x', 'extras', 0, 'kind'])).toMatchObject({ enum: expect.any(Array) })
    expect(schemaAt(systems, ['GBA', '_note'])).toBeNull()
  })
})

describe('suggest keys', () => {
  it('offers the fields an entry lacks, required ones first', () => {
    const r = run('emulators.json', '{ "x": { "name": "X", "|" } }')
    expect(labels(r)).toContain('package')
    expect(labels(r)).not.toContain('name')
    expect(r!.options.find(o => o.label === 'package')!.boost).toBeGreaterThan(r!.options.find(o => o.label === 'activity')!.boost!)
  })

  it('inserts a key with an empty value and reopens the list for it', () => {
    expect(apply('systems.json', '{ "GBA": { "ro|" } }', 'roms')).toBe('{ "GBA": { "roms": "|" } }')
    expect(apply('systems.json', '{ "GBA": { "name": "x"\n  |\n} }', 'emulators')).toBe('{ "GBA": { "name": "x",\n  "emulators": [|]\n} }')
  })

  it('keeps the value when renaming a key that has one', () => {
    expect(apply('systems.json', '{ "GBA": { "ro|": "x" } }', 'roms')).toBe('{ "GBA": { "roms"|: "x" } }')
  })

  it('adds a comma when something follows', () => {
    expect(apply('systems.json', '{ "GBA": { "|" "name": "x" } }', 'roms')).toBe('{ "GBA": { "roms": "|", "name": "x" } }')
  })

  it('offers template entries not yet defined, inserting the whole entry', () => {
    const r = run('systems.json', '{ "GBA": {}, "|" }')
    expect(labels(r)).toContain('NES')
    expect(labels(r)).not.toContain('GBA')
    const out = apply('systems.json', '{\n  "|"\n}', 'NES')
    expect(JSON.parse(out.replace('|', ''))).toEqual({ NES: (DATA.files['systems.template.json'] as Record<string, unknown>).NES })
    expect(labels(run('emulators.json', '{ "|" }'))).toEqual(expect.arrayContaining(['skyemu', 'noods']))
    expect(run('emulators.json', '{ "|" }')!.options.find(o => o.label === 'skyemu')!.detail).toContain('installed')
  })

  it('offers one kind per directory item, and name only for apps', () => {
    expect(labels(run('directory.json', '{ "items": [ { "|" } ] }'))).toEqual(expect.arrayContaining(['folder', 'system', 'shortcut', 'app']))
    const r = labels(run('directory.json', '{ "items": [ { "system": "GBA", "|" } ] }'))
    expect(r).not.toContain('app')
    expect(r).not.toContain('name')
    expect(labels(run('directory.json', '{ "items": [ { "app": "x", "|" } ] }'))).toContain('name')
  })

  it("offers an emulator's placeholders in a system's item", () => {
    const r = run('systems.json', '{ "GBA": { "emulators": [ { "emulator": "retroarch", "|" } ] } }')
    expect(labels(r)).toContain('core')
    expect(labels(run('systems.json', '{ "GBA": { "emulators": [ { "emulator": "pizzaboy", "|" } ] } }'))).not.toContain('core')
  })

  it('offers game paths in overrides.json', () => {
    const r = run('overrides.json', '{ "games": { "GBA/Golden Sun.gba": "gpSP", "|" } }')
    expect(labels(r)).toEqual(expect.arrayContaining(['NES/Zelda.nes', '/storage/1234/GBA/Pokemon.gba']))
    expect(labels(r)).not.toContain('GBA/Golden Sun.gba')
  })

  it('suggests a skeleton for an empty file', () => {
    expect(labels(run('directory.json', '|'))).toEqual(['{ … }'])
  })
})

describe('suggest values', () => {
  it('names emulators, the user\'s first, then the template\'s marked as such', () => {
    const r = run('systems.json', '{ "GBA": { "emulators": [ "|" ] } }')!
    expect(labels(r)).toEqual(expect.arrayContaining(['retroarch', 'pizzaboy', 'skyemu']))
    const sky = r.options.find(o => o.label === 'skyemu')!
    expect(sky.detail).toContain('from template')
    expect(r.options.find(o => o.label === 'retroarch')!.boost).toBeGreaterThan(sky.boost!)
    expect(apply('systems.json', '{ "GBA": { "emulators": [ "pi|" ] } }', 'pizzaboy')).toBe('{ "GBA": { "emulators": [ "pizzaboy"| ] } }')
  })

  it('leaves out emulators the list already has', () => {
    expect(labels(run('systems.json', '{ "GBA": { "emulators": [ "pizzaboy", "|" ] } }'))).not.toContain('pizzaboy')
  })

  it('offers item snippets in an empty list slot', () => {
    expect(labels(run('directory.json', '{ "items": [ | ] }'))).toEqual(expect.arrayContaining(['system', 'app', 'folder', 'shortcut']))
    expect(labels(run('emulators.json', '{ "x": { "extras": [ | ] } }'))).toContain('game path extra')
  })

  it('offers cores, the installed and the system\'s own first', () => {
    const r = run('systems.json', '{ "GBA": { "emulators": [ { "emulator": "retroarch", "core": "|" } ] } }')!
    expect(labels(r)).toEqual(expect.arrayContaining(['mgba_libretro', 'nestopia_libretro', 'snes9x_libretro']))
    const mgba = r.options.find(o => o.label === 'mgba_libretro')!
    expect(mgba.detail).toContain('installed')
    expect(r.options.find(o => o.label === 'nestopia_libretro')!.detail).toContain('not installed')
    expect(mgba.boost).toBeGreaterThan(r.options.find(o => o.label === 'snes9x_libretro')!.boost!)
  })

  it('offers RomM slugs, extensions and ROM folders from the template and device', () => {
    expect(run('systems.json', '{ "NES": { "romm": "|" } }')!.options.find(o => o.label === 'famicom')!.boost).toBe(3)
    const ext = run('systems.json', '{ "NES": { "extensions": ["nes", "|"] } }')!
    expect(labels(ext)).toContain('fds')
    expect(labels(ext)).not.toContain('nes')
    expect(labels(run('systems.json', '{ "NES": { "roms": "|" } }'))).toEqual(expect.arrayContaining(['GBA', 'NES']))
  })

  it('offers enum values with their docs', () => {
    const r = run('emulators.json', '{ "x": { "data": { "kind": "|" } } }')!
    expect(labels(r)).toEqual(['none', 'file_provider', 'absolute_path', 'external_storage_saf', 'custom_scheme'])
    expect(labels(run('shortcuts.json', '{ "x": { "data": { "kind": "|" } } }'))).toEqual(['none', 'custom_scheme'])
    expect(labels(run('shortcuts.json', '{ "x": { "extras": [ { "kind": "|" } ] } }'))).not.toContain('path')
    expect(labels(run('emulators.json', '{ "x": { "launchMethod": "|" } }'))).toEqual(['intent', 'shell'])
  })

  it('offers installed packages, the template\'s own first', () => {
    const r = run('emulators.json', '{ "skyemu": { "package": "|" } }')!
    expect(r.options[0]!.label).toBe('com.sky.SkyEmu')
    expect(r.options[0]!.boost).toBe(5)
    expect(labels(r)).toContain('com.retroarch.aarch64')
    expect(labels(run('directory.json', '{ "items": [ { "app": "|" } ] }'))).toContain('com.sky.SkyEmu')
  })

  it('offers actions and activities', () => {
    expect(labels(run('shortcuts.json', '{ "x": { "action": "|" } }'))).toEqual(expect.arrayContaining(['android.intent.action.MAIN', 'android.settings.WIFI_SETTINGS']))
    expect(labels(run('emulators.json', '{ "noods": { "package": "com.hydra.noods", "activity": "|" } }'))).toContain('com.hydra.noods.FileBrowser')
  })

  it('offers systems and shortcuts in directory.json, marking placed systems', () => {
    const r = run('directory.json', '{ "items": [ { "system": "GBA" }, { "system": "|" } ] }')!
    expect(r.options.find(o => o.label === 'GBA')!.detail).toContain('already placed')
    expect(r.options.find(o => o.label === 'SD')!.detail).not.toContain('already placed')
    expect(labels(run('directory.json', '{ "items": [ { "shortcut": "|" } ] }'))).toEqual(['kodi-movies'])
  })

  it("offers the game's system's choices in overrides.json", () => {
    const r = run('overrides.json', '{ "games": { "GBA/Golden Sun.gba": "|" } }')!
    expect(labels(r)).toEqual(['retroarch', 'gpSP', 'pizzaboy'])
    expect(r.options[0]!.detail).toContain('default')
    expect(labels(run('overrides.json', '{ "games": { "/storage/1234/GBA/x.gba": "|" } }'))).toEqual(['pizzaboy'])
  })

  it('offers placeholders inside extra values', () => {
    const r = run('emulators.json', '{ "x": { "extras": [ { "key": "a", "kind": "string", "value": "/x/{ro|" } ] } }')!
    expect(labels(r)).toEqual(expect.arrayContaining(['{rom_path}', '{package}', '{core}']))
    expect(apply('emulators.json', '{ "x": { "extras": [ { "kind": "string", "value": "/x/{ro|}" } ] } }', '{rom_path}'))
      .toBe('{ "x": { "extras": [ { "kind": "string", "value": "/x/{rom_path}|" } ] } }')
    expect(labels(run('shortcuts.json', '{ "x": { "extras": [ { "kind": "string", "value": "{|" } ] } }'))).toEqual(['{package}'])
  })

  it('offers nothing in an empty spot until asked', () => {
    expect(run('systems.json', '{ "GBA": { "roms": | } }', false)).toBeNull()
    expect(run('systems.json', '{ "GBA": { "roms": | } }', true)).not.toBeNull()
  })
})

describe('validate', () => {
  /** Errors and warnings; the info notes (such as "not installed") are tested on their own. */
  const issues = (file: string, text: string) =>
    validate(file, text, DATA).filter(i => i.severity !== 'info').map(i => ({ ...i, text: text.slice(i.from, i.to) }))

  it('notes packages the device lacks and ROM folders it has not got', () => {
    const r = validate('emulators.json', '{ "a": { "package": "com.retroarch.aarch64" }, "b": { "package": "nope.app" } }', DATA)
    expect(r).toEqual([expect.objectContaining({ severity: 'info', message: expect.stringContaining("nope.app isn't installed") })])
    expect(validate('systems.json', '{ "X": { "roms": "Nowhere" } }', DATA)[0]!.message).toContain('no folder Roms/Nowhere')
  })

  it('reports a JSON error at its place', () => {
    const r = validate('systems.json', '{ "a": }', DATA)
    expect(r).toHaveLength(1)
    expect(r[0]).toMatchObject({ severity: 'error', from: 7 })
  })

  it('warns about unknown keys, but not comments', () => {
    const r = issues('systems.json', '{ "GBA": { "_note": "x", "rom": "GBA", "emulators": ["pizzaboy"] } }')
    expect(r).toHaveLength(1)
    expect(r[0]).toMatchObject({ severity: 'warning', text: '"rom"' })
    expect(r[0]!.message).toContain('did you mean "roms"')
  })

  it('reports wrong types, bad enum values and missing fields', () => {
    const r = issues('emulators.json', '{ "x": { "intentFlags": "1", "launchMethod": "adb", "data": { "kind": "custom_scheme" } } }')
    expect(r.map(i => i.message)).toEqual(expect.arrayContaining([
      expect.stringContaining('whole number'),
      expect.stringContaining('"adb" isn\'t one of'),
      expect.stringContaining('custom_scheme needs "scheme"'),
      expect.stringContaining('Needs "package"'),
    ]))
  })

  it('checks extras by kind', () => {
    const r = issues('emulators.json', '{ "x": { "package": "p", "extras": [ { "key": "a", "kind": "string" }, { "key": "b", "kind": "path", "value": "v" } ] } }')
    expect(r.map(i => i.message)).toEqual([expect.stringContaining('needs a "value"'), expect.stringContaining('"value" is ignored')])
  })

  it('reports names that refer to nothing', () => {
    const r = issues('systems.json', '{ "GBA": { "emulators": ["skyemu", "nowhere", { "emulator": "retroarch" }] } }')
    expect(r.map(i => i.message)).toEqual([
      expect.stringContaining('emulators.template.json has it'),
      expect.stringContaining('No emulator "nowhere"'),
      expect.stringContaining('retroarch uses {core}'),
    ])
    expect(issues('directory.json', '{ "items": [ { "system": "GBA" }, { "folder": "F", "items": [ { "system": "GBA" }, { "shortcut": "zz" } ] } ] }').map(i => i.message))
      .toEqual([expect.stringContaining('already placed'), expect.stringContaining('No shortcut "zz"')])
  })

  it('grades issues as the launcher does: malformed is an error, a dropped reference or repeat a warning', () => {
    const sev = (r: { severity: string; message: string }[], part: string) => r.find(i => i.message.includes(part))?.severity
    const systems = issues('systems.json', '{ "GBA": { "emulators": ["pizzaboy", "pizzaboy", "nowhere", { "emulator": "retroarch" }, { "name": "x" }] } }')
    expect(sev(systems, 'already listed')).toBe('warning')
    expect(sev(systems, 'No emulator "nowhere"')).toBe('warning')
    expect(sev(systems, 'retroarch uses {core}')).toBe('error')
    expect(sev(systems, 'Needs "emulator"')).toBe('error')
    const directory = issues('directory.json', '{ "items": [ { "system": "GBA" }, { "system": "GBA" }, { "shortcut": "zz" }, { "name": "x" } ] }')
    expect(sev(directory, 'already placed')).toBe('warning')
    expect(sev(directory, 'No shortcut "zz"')).toBe('warning')
    expect(sev(directory, 'Needs one of')).toBe('error')
  })

  it('checks directory items have exactly one kind', () => {
    expect(issues('directory.json', '{ "items": [ { "system": "GBA", "app": "x" } ] }')[0]!.message).toContain('Only one of')
    expect(issues('directory.json', '{ "items": [ { "name": "x" } ] }')[0]!.message).toContain('Needs one of')
  })

  it('checks overrides against the systems', () => {
    const r = issues('overrides.json', '{ "games": { "GBA/a.gba": "gpSP", "GBA/b.gba": "nope", "XX/c.bin": "x" } }')
    expect(r.map(i => i.message)).toEqual([
      expect.stringContaining('"nope" isn\'t one of GBA\'s emulators'),
      expect.stringContaining("No system's ROM folder holds this game"),
    ])
  })

  it('rejects placeholders a shortcut cannot fill, and game kinds', () => {
    const r = issues('shortcuts.json', '{ "s": { "package": "p", "extras": [ { "key": "k", "kind": "path" }, { "key": "k2", "kind": "string", "value": "{rom_path}" } ] } }')
    expect(r.map(i => i.message)).toEqual([expect.stringContaining('takes no game, so "path"'), expect.stringContaining('nothing fills {rom_path}')])
  })

  it('accepts the files as the docs write them', () => {
    expect(issues('systems.json', JSON.stringify(DATA.files['systems.json']))).toEqual([])
    expect(issues('emulators.json', JSON.stringify(DATA.files['emulators.json']))).toEqual([])
    expect(issues('directory.json', JSON.stringify(DATA.files['directory.json']))).toEqual([])
  })

  it('checks only the JSON of templates', () => {
    expect(validate('systems.template.json', '{ "X": { "bogus": 1 } }', DATA)).toEqual([])
  })
})

describe('describe', () => {
  it('explains keys, and where a named emulator is defined', () => {
    const [text, pos] = at('{ "GBA": { "ro|ms": "GBA", "emulators": ["skyemu"] } }')
    expect(describeAt('systems.json', text, pos, DATA)!.title).toContain('roms')
    const [t2, p2] = at('{ "GBA": { "emulators": ["sky|emu"] } }')
    expect(describeAt('systems.json', t2, p2, DATA)!.body.join(' ')).toContain('only in emulators.template.json')
    const [t3, p3] = at('{ "items": [ { "system": "G|BA" } ] }')
    expect(describeAt('directory.json', t3, p3, DATA)!.body.join(' ')).toContain('Game Boy Advance, defined in systems.json')
  })

  it('says which systems use an emulator', () => {
    const [text, pos] = at('{ "piz|zaboy": {} }')
    expect(describeAt('emulators.json', text, pos, DATA)!.body.join(' ')).toContain('Used by GBA, SD')
  })
})

describe('index', () => {
  it('matches a game to the system with the longest folder', () => {
    const index = indexOf(DATA)
    expect(systemForGame(index, 'gba/x.gba')?.key).toBe('GBA')
    expect(systemForGame(index, '/storage/1234/GBA/x.gba')?.key).toBe('SD')
    expect(index.params.has('core')).toBe(true)
    expect(index.emulators.get('retroarch')!.params).toEqual(['core'])
  })
})
