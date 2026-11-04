/**
 * What each config file may hold, written from the app's parsers (LibraryParser,
 * PlatformConfig.parseLaunchConfig, Placeholders) and CONFIG.md. The editor completes, explains and
 * checks from these. Keys starting with `_` are comments everywhere and always allowed.
 *
 * [Hint]s and [KeyHint]s name the suggestions that come from the other files, the templates or
 * the device rather than from the schema itself (see suggest.ts). [Rule]s name the checks a type
 * can't express, such as "an extra of kind string needs a value" (see validate.ts).
 */

import type { Path } from './jsonContext'

export interface Choice {
  value: string | number | boolean | null
  doc?: string
}

/** Suggestions for a value, from outside the schema. */
export type Hint =
  | 'emulator' // a key of emulators.json
  | 'system' // a key of systems.json
  | 'shortcut' // a key of shortcuts.json
  | 'package' // an installed app
  | 'activity' // activities the templates use for the entry's package
  | 'action' // intent actions the templates use
  | 'extraKey' // extra keys the templates use for the entry's package
  | 'core' // libretro cores
  | 'romm' // RomM platform slugs
  | 'extension' // file types
  | 'romFolder' // folders under Roms/
  | 'emulatorChoice' // one of a system's emulator choices
  | 'param' // a placeholder value a system gives its emulator

/** Suggestions for a new key, from outside the schema. */
export type KeyHint = 'newEmulator' | 'newSystem' | 'newShortcut' | 'game' | 'param'

export type Rule = 'extra' | 'shortcutExtra' | 'data' | 'shortcutData' | 'directoryItem' | 'emulatorRef' | 'system' | 'shortcut'

/** A ready-made value for a list, such as a whole directory item. `¦` marks where the cursor goes. */
export interface Snippet {
  label: string
  text: string
  doc?: string
}

interface Base {
  doc?: string
  /** Shown as the value's type in completions and hover. */
  title?: string
}

export interface ObjectSchema extends Base {
  type: 'object'
  props?: Record<string, Schema>
  required?: string[]
  /** The schema of keys not in [props]; without it, other keys are unknown and get a warning. */
  additional?: Schema
  keyHint?: KeyHint
  /** What a key of [additional] is, for hover. */
  keyDoc?: string
  rule?: Rule
}
export interface ArraySchema extends Base {
  type: 'array'
  items: Schema
  snippets?: Snippet[]
}
export interface StringSchema extends Base {
  type: 'string'
  /** The only values allowed. */
  enum?: Choice[]
  /** Common values, others allowed. */
  suggest?: Choice[]
  hint?: Hint
  /** `{placeholder}`s are filled in this value: offer them. */
  placeholders?: 'emulator' | 'shortcut'
}
export interface ScalarSchema extends Base {
  type: 'integer' | 'boolean' | 'null' | 'number'
  suggest?: Choice[]
}
export interface AnyOfSchema extends Base {
  type: 'anyOf'
  anyOf: Schema[]
}

export type Schema = ObjectSchema | ArraySchema | StringSchema | ScalarSchema | AnyOfSchema

export const BUILT_IN_PLACEHOLDERS: Choice[] = [
  { value: 'rom_path', doc: "The game file's absolute path." },
  { value: 'package', doc: "The emulator's package." },
  { value: 'rom_extension', doc: "The file's extension, in lower case." },
  { value: 'rom_contents', doc: 'The first non-blank line of the file.' },
  { value: 'rom_id', doc: 'The first line without a `[tag]` prefix, so `[steamappid] 570` gives `570`.' },
]
export const BUILT_IN_NAMES = new Set(BUILT_IN_PLACEHOLDERS.map(c => c.value as string))

export const DATA_KINDS: Choice[] = [
  { value: 'none', doc: 'No data URI (the default when `data` is left out).' },
  { value: 'file_provider', doc: "A content:// URI from Cannoli's file provider. Takes `grantPermission` (true by default)." },
  { value: 'absolute_path', doc: 'A file:// URI for the game.' },
  { value: 'external_storage_saf', doc: 'A storage-access-framework document URI for the file.' },
  { value: 'custom_scheme', doc: '`scheme://authority/<file path>` (for a shortcut, `scheme://authority`). Needs `scheme` and `authority`.' },
]
export const SHORTCUT_DATA_KINDS = DATA_KINDS.filter(c => c.value === 'none' || c.value === 'custom_scheme')

export const EXTRA_KINDS: Choice[] = [
  { value: 'path', doc: "The game file's absolute path, as text." },
  { value: 'uri_string', doc: 'A content:// URI for the game, as text.' },
  { value: 'uri_parcelable', doc: 'The same URI, as a Uri.' },
  { value: 'string', doc: 'Text. Takes a `value`, which can contain placeholders.' },
  { value: 'int', doc: 'A number. Takes a `value`, which can contain placeholders.' },
  { value: 'bool', doc: 'true or false. Takes a `value`, which can contain placeholders.' },
  { value: 'string_array', doc: 'A list of text. Takes `values`, which can contain placeholders.' },
]
export const ROM_EXTRA_KINDS = new Set(['path', 'uri_string', 'uri_parcelable'])
export const SHORTCUT_EXTRA_KINDS = EXTRA_KINDS.filter(c => !ROM_EXTRA_KINDS.has(c.value as string))

export const ACTIONS: Choice[] = [
  { value: 'android.intent.action.VIEW', doc: 'Open the data URI. The default for emulators.' },
  { value: 'android.intent.action.MAIN', doc: 'Start the app or activity. The default for shortcuts.' },
  { value: 'android.intent.action.SEND', doc: 'Share the data with the app.' },
  { value: 'android.settings.SETTINGS', doc: "Android's Settings." },
  { value: 'android.settings.WIFI_SETTINGS', doc: 'Wi-Fi settings.' },
  { value: 'android.settings.BLUETOOTH_SETTINGS', doc: 'Bluetooth settings.' },
  { value: 'android.settings.DISPLAY_SETTINGS', doc: 'Display settings.' },
  { value: 'android.settings.SOUND_SETTINGS', doc: 'Sound settings.' },
  { value: 'android.settings.APPLICATION_DETAILS_SETTINGS', doc: "An app's info page (needs data package:<name>)." },
]

export const MIME_TYPES: Choice[] = [
  { value: '*/*', doc: 'Any type: the default for emulators.' },
  { value: 'application/octet-stream', doc: 'Binary data.' },
  { value: 'application/zip', doc: 'A zip archive.' },
  { value: 'application/x-7z-compressed', doc: 'A 7z archive.' },
  { value: 'application/x-iso9660-image', doc: 'A disc image.' },
  { value: 'text/plain', doc: 'Text.' },
  { value: null, doc: 'Send no MIME type.' },
]

export const INTENT_FLAGS: Choice[] = [
  { value: 268435456, doc: 'FLAG_ACTIVITY_NEW_TASK, the default.' },
  { value: 268468224, doc: 'NEW_TASK | CLEAR_TASK: start the app fresh.' },
  { value: 335544320, doc: 'NEW_TASK | CLEAR_TOP.' },
  { value: 1342210048, doc: 'NEW_TASK | CLEAR_TASK | NO_HISTORY.' },
]

const str = (doc: string, extra: Omit<StringSchema, 'type' | 'doc'> = {}): StringSchema => ({ type: 'string', doc, ...extra })
const strList = (doc: string, items: StringSchema): ArraySchema => ({ type: 'array', doc, items })

function extraSchema(shortcut: boolean): ObjectSchema {
  const placeholders = shortcut ? 'shortcut' : 'emulator'
  return {
    type: 'object',
    title: 'extra',
    doc: 'An intent extra: `{ "key", "kind", ... }`.',
    required: ['key', 'kind'],
    rule: shortcut ? 'shortcutExtra' : 'extra',
    props: {
      key: str("The extra's name, as the app reads it.", { hint: 'extraKey' }),
      kind: str('What the extra holds.', { enum: shortcut ? SHORTCUT_EXTRA_KINDS : EXTRA_KINDS }),
      value: {
        type: 'anyOf',
        doc: 'The value, for kinds string, int and bool. Can contain placeholders such as `{rom_path}`. An empty result is not sent.',
        anyOf: [str('', { placeholders }), { type: 'number' }, { type: 'boolean' }],
      },
      values: strList('The values, for kind string_array. Each can contain placeholders.', str('', { placeholders })),
      map: {
        type: 'object',
        doc: 'A lookup table applied to the value after placeholders are filled in. A value the table has no entry for drops the extra.',
        additional: str('What this value becomes.'),
        keyDoc: 'A value to look up.',
      },
      whenExtension: strList('Send the extra only for games with one of these file extensions.', str('A file extension, without the dot.', { hint: 'extension' })),
    },
  }
}

function dataSchema(shortcut: boolean): ObjectSchema {
  return {
    type: 'object',
    title: 'data',
    doc: shortcut
      ? 'The intent\'s data URI: `{ "kind": "none" }` or `custom_scheme`, which opens `scheme://authority`.'
      : 'How the game is passed as the intent\'s data URI.',
    required: ['kind'],
    rule: shortcut ? 'shortcutData' : 'data',
    props: {
      kind: str('How the data URI is made.', { enum: shortcut ? SHORTCUT_DATA_KINDS : DATA_KINDS }),
      grantPermission: { type: 'boolean', doc: 'For file_provider: grant the app read access to the file. True by default.' },
      scheme: str('For custom_scheme: the URI scheme, such as `https` or the app\'s own.'),
      authority: str('For custom_scheme: the part after `scheme://`.'),
    },
  }
}

function launchIntent(shortcut: boolean): Record<string, Schema> {
  return {
    package: str("The app's package name. Required.", { hint: 'package' }),
    activity: str('The activity to start. Without one, the intent goes to the package.', { hint: 'activity' }),
    action: str(shortcut
      ? 'The intent action. Defaults to android.intent.action.MAIN, which opens the app like its home-screen icon.'
      : 'The intent action. Defaults to android.intent.action.VIEW.', { suggest: ACTIONS, hint: 'action' }),
    mimeType: {
      type: 'anyOf',
      doc: shortcut ? 'The MIME type sent with the data URI. Defaults to none.' : 'The MIME type sent with the data URI. Defaults to `*/*`; null sends none.',
      anyOf: [str('', { suggest: MIME_TYPES.filter(c => c.value !== null) }), { type: 'null', doc: 'Send no MIME type.' }],
    },
    intentFlags: { type: 'integer', doc: 'The intent flags as an integer. Defaults to FLAG_ACTIVITY_NEW_TASK (268435456).', suggest: INTENT_FLAGS },
    launchMethod: str('How Cannoli starts it.', {
      enum: [
        { value: 'intent', doc: 'Start the intent (the default).' },
        { value: 'shell', doc: 'Start it with `am start`.' },
      ],
    }),
    data: dataSchema(shortcut),
    extras: {
      type: 'array',
      doc: 'Intent extras, each `{ "key", "kind", ... }`.',
      items: extraSchema(shortcut),
      snippets: shortcut
        ? [
            { label: 'string extra', text: '{ "key": "¦", "kind": "string", "value": "" }' },
            { label: 'int extra', text: '{ "key": "¦", "kind": "int", "value": "" }' },
            { label: 'bool extra', text: '{ "key": "¦", "kind": "bool", "value": "true" }' },
          ]
        : [
            { label: 'game path extra', text: '{ "key": "¦", "kind": "path" }', doc: "The game file's absolute path, as text." },
            { label: 'content URI extra', text: '{ "key": "¦", "kind": "uri_string" }', doc: 'A content:// URI for the game, as text.' },
            { label: 'Uri extra', text: '{ "key": "¦", "kind": "uri_parcelable" }', doc: 'A content:// URI for the game, as a Uri.' },
            { label: 'string extra', text: '{ "key": "¦", "kind": "string", "value": "{rom_path}" }' },
            { label: 'string_array extra', text: '{ "key": "¦", "kind": "string_array", "values": ["{rom_path}"] }' },
          ],
    },
  }
}

const emulatorEntry: ObjectSchema = {
  type: 'object',
  title: 'emulator',
  doc: 'An emulator: a display name and a launch intent.',
  required: ['package'],
  props: {
    name: str('Shown in the pickers. Defaults to the key.'),
    ...launchIntent(false),
  },
}

const shortcutEntry: ObjectSchema = {
  type: 'object',
  title: 'shortcut',
  doc: 'A launch intent that acts like an app on the menu. It takes no game.',
  required: ['package'],
  rule: 'shortcut',
  props: {
    name: str('Shown on the menu. Defaults to the key.'),
    ...launchIntent(true),
  },
}

const emulatorRef: ObjectSchema = {
  type: 'object',
  title: 'emulator choice',
  doc: 'An emulator plus a value for each placeholder it uses, such as `"core"`.',
  required: ['emulator'],
  rule: 'emulatorRef',
  props: {
    emulator: str("The emulator's key in emulators.json.", { hint: 'emulator' }),
    name: str('What pickers and overrides.json call this choice. Needed when the same emulator is listed twice.'),
    core: str('The libretro core, filling `{core}`, such as `mgba_libretro`.', { hint: 'core' }),
  },
  additional: {
    type: 'anyOf',
    doc: 'Fills the placeholder of this name in the emulator\'s extras.',
    anyOf: [str('', { hint: 'param' }), { type: 'number' }, { type: 'boolean' }],
  },
  keyHint: 'param',
  keyDoc: 'A placeholder the emulator uses, filled with this value.',
}

const systemEntry: ObjectSchema = {
  type: 'object',
  title: 'system',
  doc: 'A system: its ROM folder, file types and the emulators that run it.',
  rule: 'system',
  props: {
    name: str('The display name. Defaults to the key.'),
    roms: str('Its folder under Roms/. Defaults to the key. A value starting with `/` is a folder anywhere on the device.', { hint: 'romFolder' }),
    extensions: strList(
      'The file types that are games, without the dot. Without it, every file is a game. An empty list is ignored.',
      str('A file type, without the dot.', { hint: 'extension' }),
    ),
    romm: {
      type: 'anyOf',
      doc: "The RomM platform slug whose games are this system's, or a list of them.",
      anyOf: [str('', { hint: 'romm' }), strList('', str('A RomM platform slug.', { hint: 'romm' }))],
    },
    arcade: { type: 'boolean', doc: 'true for arcade romsets.' },
    emulators: {
      type: 'array',
      doc: 'The emulators that can run it, in order. The first one is the default.',
      items: {
        type: 'anyOf',
        doc: 'An emulator key, or an object with `emulator` and its placeholder values.',
        anyOf: [str("An emulator's key in emulators.json.", { hint: 'emulator' }), emulatorRef],
      },
      snippets: [
        { label: 'RetroArch with a core', text: '{ "emulator": "retroarch", "core": "¦" }', doc: 'RetroArch, filling `{core}`.' },
        { label: 'emulator with values', text: '{ "emulator": "¦" }', doc: 'An emulator plus values for its placeholders.' },
      ],
    },
  },
}

const directoryItem: ObjectSchema = {
  type: 'object',
  title: 'menu item',
  doc: 'One of `folder`, `system`, `shortcut` or `app`.',
  rule: 'directoryItem',
  props: {
    folder: str('A folder of this name, holding its own `items`.'),
    system: str('A key from systems.json. It shows once it has games, and goes in one place only.', { hint: 'system' }),
    shortcut: str('A key from shortcuts.json.', { hint: 'shortcut' }),
    app: str('A package name, opened as its home-screen icon would be.', { hint: 'package' }),
    name: str("For an app: the name shown. Without one, Cannoli uses the app's own label."),
  },
}
const directoryItems: ArraySchema = {
  type: 'array',
  doc: 'Menu items, in order.',
  items: directoryItem,
  snippets: [
    { label: 'system', text: '{ "system": "¦" }', doc: 'A system from systems.json.' },
    { label: 'shortcut', text: '{ "shortcut": "¦" }', doc: 'A shortcut from shortcuts.json.' },
    { label: 'app', text: '{ "app": "¦" }', doc: 'An installed app.' },
    { label: 'folder', text: '{ "folder": "¦", "items": [] }', doc: 'A folder with its own items.' },
  ],
}
directoryItem.props!.items = { ...directoryItems, doc: "A folder's items, in order." }

export const FILE_SCHEMAS: Record<string, ObjectSchema> = {
  'emulators.json': {
    type: 'object',
    doc: 'Emulators, by name. systems.json lists them by these keys.',
    additional: emulatorEntry,
    keyHint: 'newEmulator',
    keyDoc: "The emulator's name, which systems.json uses.",
  },
  'systems.json': {
    type: 'object',
    doc: 'Systems, by ID. directory.json places them by these keys.',
    additional: systemEntry,
    keyHint: 'newSystem',
    keyDoc: "The system's ID, which directory.json and the ROM folder default use.",
  },
  'shortcuts.json': {
    type: 'object',
    doc: 'Shortcuts, by name. directory.json places them by these keys.',
    additional: shortcutEntry,
    keyHint: 'newShortcut',
    keyDoc: "The shortcut's name, which directory.json uses.",
  },
  'directory.json': {
    type: 'object',
    doc: 'The main menu.',
    required: ['items'],
    props: { items: { ...directoryItems, doc: 'The main menu, in order, after Recents, Favorites and Collections.' } },
  },
  'overrides.json': {
    type: 'object',
    doc: 'The emulator picked for single games.',
    required: ['games'],
    props: {
      games: {
        type: 'object',
        doc: 'Game path → emulator choice.',
        additional: str("One item of the game's system's `emulators` list: its `name`, or else its emulator key.", { hint: 'emulatorChoice' }),
        keyHint: 'game',
        keyDoc: "The game's path under Roms/, starting with its system's folder (or its full path for a system whose `roms` starts with `/`).",
      },
    },
  },
}
// The templates hold the same entries, read-only.
FILE_SCHEMAS['emulators.template.json'] = FILE_SCHEMAS['emulators.json']!
FILE_SCHEMAS['systems.template.json'] = FILE_SCHEMAS['systems.json']!
FILE_SCHEMAS['shortcuts.template.json'] = FILE_SCHEMAS['shortcuts.json']!

export const isComment = (key: string) => key.startsWith('_')

export const COMMENT_DOC = 'A comment: Cannoli ignores every key starting with `_`.'

/** The schema for the value at [path], or null where nothing is known. */
export function schemaAt(root: Schema, path: Path): Schema | null {
  let node: Schema | null = root
  for (const seg of path) {
    if (!node) return null
    node = child(node, seg)
  }
  return node
}

/** The schema of [seg] inside a value of [schema]. */
export function child(schema: Schema, seg: string | number): Schema | null {
  if (schema.type === 'anyOf') {
    for (const option of schema.anyOf) {
      const found = child(option, seg)
      if (found) return found
    }
    return null
  }
  if (typeof seg === 'number') return schema.type === 'array' ? schema.items : null
  if (schema.type !== 'object') return null
  if (isComment(seg)) return null
  return schema.props?.[seg] ?? schema.additional ?? null
}

/** The object schemas [schema] can be (itself, or the object options of an anyOf). */
export function objectsOf(schema: Schema | null): ObjectSchema[] {
  if (!schema) return []
  if (schema.type === 'object') return [schema]
  if (schema.type === 'anyOf') return schema.anyOf.flatMap(objectsOf)
  return []
}

/** The leaf schemas [schema] can be, with anyOf flattened. */
export function optionsOf(schema: Schema | null): Schema[] {
  if (!schema) return []
  if (schema.type === 'anyOf') return schema.anyOf.flatMap(optionsOf)
  return [schema]
}

/** The schema's own doc, or that of the anyOf it came from. */
export function docOf(schema: Schema | null): string {
  return schema?.doc ?? ''
}

export function typeName(schema: Schema): string {
  if (schema.title) return schema.title
  switch (schema.type) {
    case 'anyOf': return [...new Set(schema.anyOf.map(typeName))].join(' or ')
    case 'array': return 'list'
    case 'object': return 'object'
    case 'string': return 'text'
    case 'integer': return 'integer'
    case 'number': return 'number'
    case 'boolean': return 'true/false'
    case 'null': return 'null'
  }
}
