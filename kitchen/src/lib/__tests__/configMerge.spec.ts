import { describe, expect, it } from 'vitest'
import {
  checkJson,
  findJsonError,
  emulatorRefs,
  isTemplate,
  mergeEntries,
  missingEmulators,
  parseObject,
  placeInDirectory,
  templateEntries,
  templateTarget,
} from '../configMerge'

const systemsTemplate = {
  _about: ['comment'],
  GBA: {
    _note: 'Handheld.',
    name: 'Game Boy Advance',
    emulators: [{ emulator: 'retroarch', core: 'mgba_libretro' }, { emulator: 'retroarch', core: 'gpsp_libretro', name: 'gpSP' }, 'pizzaboy'],
  },
  GB: { name: 'Game Boy', emulators: ['retroarch', 'mystery'] },
  NOOBJ: ['not', 'an', 'entry'],
}

const emulatorsTemplate = {
  _about: 'x',
  retroarch: { name: 'RetroArch', package: 'com.retroarch.aarch64' },
  pizzaboy: { name: 'Pizza Boy GBA', package: 'it.dbtecno.pizzaboygba' },
}

describe('templates', () => {
  it('maps a template to its real file', () => {
    expect(templateTarget('systems.template.json')).toBe('systems.json')
    expect(templateTarget('emulators.template.json')).toBe('emulators.json')
    expect(templateTarget('directory.json')).toBeNull()
    expect(isTemplate('shortcuts.template.json')).toBe(true)
  })

  it('lists entries without comment keys or non-objects', () => {
    const entries = templateEntries(systemsTemplate)
    expect(entries.map(e => e.key)).toEqual(['GBA', 'GB'])
    expect(entries[0]).toEqual({
      key: 'GBA',
      name: 'Game Boy Advance',
      note: 'Handheld.',
      emulators: ['retroarch', 'pizzaboy'],
    })
  })

  it('reads emulator references from names and objects', () => {
    expect(emulatorRefs({ emulators: ['a', { emulator: 'b', core: 'c' }, { core: 'x' }, 'a'] })).toEqual(['a', 'b'])
    expect(emulatorRefs({})).toEqual([])
    expect(emulatorRefs(null)).toEqual([])
  })
})

describe('checkJson', () => {
  it('accepts valid JSON', () => {
    expect(checkJson('{ "a": 1 }').ok).toBe(true)
  })

  it('finds the line and column of an error', () => {
    const text = '{\n  "a": 1,\n  "b": }\n'
    const result = checkJson(text)
    expect(result.ok).toBe(false)
    expect(result.line).toBe(3)
    expect(result.column).toBe(8)
    expect(result.offset).toBe(text.indexOf('}'))
  })

  it('names common mistakes', () => {
    expect(checkJson('{ "a": 1, }').message).toBe('trailing comma')
    expect(checkJson('[1, 2,]').message).toBe('trailing comma')
    expect(checkJson("{ 'a': 1 }").message).toBe('expected a "key"')
    expect(checkJson('{ "a": "x\\n" }').ok).toBe(true)
    expect(checkJson('{ "a": "x\n}').message).toContain('line break')
    expect(checkJson('{ "a": 1 "b": 2 }')).toMatchObject({ message: 'expected "," or "}"', line: 1, column: 10 })
    expect(checkJson('{ "a": [1, 2').message).toBe('unexpected end of text')
    expect(checkJson('{} x').message).toBe('unexpected text after the end')
    expect(findJsonError('{ "a": [true, false, null, -1.5e3, "\\u00e9"] }')).toBeNull()
  })
})

describe('mergeEntries', () => {
  it('adds missing entries at the end and never overwrites', () => {
    const existing = '{\n  "GB": { "name": "My Game Boy" }\n}\n'
    const result = mergeEntries(existing, systemsTemplate, ['GBA', 'GB', 'NOPE', '_about'])
    expect(result.added).toEqual(['GBA'])
    expect(result.skipped).toEqual(['GB'])
    expect(result.missing).toEqual(['NOPE'])
    const merged = JSON.parse(result.text)
    expect(Object.keys(merged)).toEqual(['GB', 'GBA'])
    expect(merged.GB).toEqual({ name: 'My Game Boy' })
    expect(merged.GBA._note).toBe('Handheld.')
    expect(result.text).toContain('\n  "GBA": {\n    "_note"')
    expect(result.text.endsWith('}\n')).toBe(true)
  })

  it('treats an empty file as an empty object and leaves text alone when nothing is added', () => {
    expect(JSON.parse(mergeEntries('', systemsTemplate, ['GB']).text)).toHaveProperty('GB')
    const same = '{ "GB": {} }'
    expect(mergeEntries(same, systemsTemplate, ['GB']).text).toBe(same)
  })

  it('refuses a file that is not an object', () => {
    expect(() => mergeEntries('[]', systemsTemplate, ['GB'])).toThrow()
    expect(() => parseObject('{ broken')).toThrow()
  })
})

describe('missingEmulators', () => {
  it('lists the emulators selected systems need that the file lacks', () => {
    const have = '{ "retroarch": {} }'
    expect(missingEmulators(systemsTemplate, ['GBA', 'GB'], have, emulatorsTemplate)).toEqual({
      inTemplate: ['pizzaboy'],
      unknown: ['mystery'],
    })
  })
})

describe('placeInDirectory', () => {
  it('appends systems the directory does not place anywhere', () => {
    const directory = JSON.stringify({ items: [{ folder: 'Nintendo', items: [{ system: 'GB' }] }, { app: 'x' }] })
    const result = placeInDirectory(directory, 'system', ['GB', 'GBA', 'GBA'])
    expect(result.added).toEqual(['GBA'])
    expect(JSON.parse(result.text).items).toEqual([
      { folder: 'Nintendo', items: [{ system: 'GB' }] },
      { app: 'x' },
      { system: 'GBA' },
    ])
  })

  it('creates the items list when the file has none', () => {
    expect(JSON.parse(placeInDirectory('{}', 'shortcut', ['wifi']).text)).toEqual({ items: [{ shortcut: 'wifi' }] })
  })
})
