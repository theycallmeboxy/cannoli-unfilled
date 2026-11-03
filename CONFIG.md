# Configuration files

Cannoli Unfilled has no built-in emulator and no built-in list of systems. What it shows and how
it launches games come from five JSON files in `Config/` under the Cannoli folder. You can edit
them by hand, on the device, or through Kitchen.

Cannoli re-reads the files each time you return to it, so you don't need to restart.

## The files

| File | Holds | Refers to | Written by |
| --- | --- | --- | --- |
| `emulators.json` | Emulators: a name and a launch intent | nothing | you |
| `systems.json` | Systems: a ROM folder, file types, and the emulators that run it | `emulators.json` | you, and "Default Emulator" |
| `shortcuts.json` | Launch intents that act like an app, such as a Kodi add-on | nothing | you |
| `directory.json` | The menu: folders, systems, shortcuts and apps, in order | `systems.json`, `shortcuts.json` | you, and folder editing |
| `overrides.json` | The emulator picked for single games | `systems.json` | "Launch With" |

Three reference files sit next to them:

| File | Holds |
| --- | --- |
| `emulators.template.json` | Every emulator Cannoli knows how to launch: RetroArch and the standalone emulators |
| `systems.template.json` | Every system Cannoli knows, with its ROM folder and emulators |
| `shortcuts.template.json` | A few example shortcuts |

Cannoli rewrites the templates whenever their text differs from the app's copy, and never reads
them as config. Don't edit them: copy entries out of them into the real files.

The files refer to each other by name. A system lists emulators by their key in
`emulators.json`. The directory lists systems by their key in `systems.json` and shortcuts by
their key in `shortcuts.json`. An override names one of its system's emulators.

### Comments

JSON has no comments. Instead, Cannoli ignores every key that starts with `_`, in every file and
at every level. The templates use `_about` and `_note` for their explanations, and you can keep
a `_note` in an entry you copy, or add your own.

## Getting started

A new install starts with every real file empty, so the main menu shows no systems. Existing
installs start empty too: nothing is converted from older config files.

To add a system by hand:

1. Open `systems.template.json` and find the system, for example `"GBA"`.
2. Copy its entry into `systems.json`, keeping the key.
3. Look at its `emulators` list. For each name in it (`"retroarch"`, `"pizzaboy"`...), copy the
   entry with that key from `emulators.template.json` into `emulators.json`. You only need the
   ones you have installed, but every name the system lists must be in `emulators.json`, or that
   item is skipped.
4. Place the system in `directory.json`, for example `{ "system": "GBA" }` in the top-level
   `items` list.
5. Put games in its ROM folder, `Roms/GBA/`, and return to Cannoli.

You can do steps 4 and 5 the other way round, and Cannoli can do most of this for you:

- **A ROM folder with no system:** if `Roms/GBA/` holds files but no system in `systems.json`
  uses that folder, it's listed under **Not in the menu** while you edit (see
  [Editing on the device](#editing-on-the-device)). Picking it offers to copy the matching system
  from `systems.template.json`, and the emulators it lists that aren't in `emulators.json` yet.
  Once copied, Cannoli scans its games and asks where to place it. The template system is the one whose key, or else whose `roms`, is the
  folder name, ignoring case.
- **A system with games that isn't placed:** it's listed under **Not in the menu** too, and
  picking it asks where to place it.

A system with no games never shows on the menu, placed or not.

## `emulators.json`

```json
{
  "retroarch": {
    "name": "RetroArch",
    "package": "com.retroarch.aarch64",
    "activity": "com.retroarch.browser.retroactivity.RetroActivityFuture",
    "action": "android.intent.action.MAIN",
    "extras": [
      { "key": "ROM", "kind": "string", "value": "{rom_path}" },
      { "key": "LIBRETRO", "kind": "string", "value": "/data/data/{package}/cores/{core}_android.so" },
      { "key": "CONFIGFILE", "kind": "string", "value": "/storage/emulated/0/Android/data/{package}/files/retroarch.cfg" }
    ]
  },
  "pizzaboy": {
    "name": "Pizza Boy GBA",
    "package": "it.dbtecno.pizzaboygba",
    "extras": [ { "key": "rom_uri", "kind": "uri_string" } ]
  }
}
```

- **Key:** the emulator's name. `systems.json` uses it.
- **`name`:** optional, shown in the pickers. Defaults to the key.
- Everything else is a [launch intent](#launch-intents).

RetroArch is an emulator entry like any other. One `retroarch` entry serves every core, because
each system fills in `{core}`.

## `systems.json`

```json
{
  "GBA": {
    "name": "Game Boy Advance",
    "roms": "GBA",
    "extensions": ["gb", "gba", "gbc", "zip"],
    "romm": "gba",
    "emulators": [
      { "emulator": "retroarch", "core": "mgba_libretro" },
      { "emulator": "retroarch", "core": "gpsp_libretro", "name": "gpSP" },
      "pizzaboy"
    ]
  }
}
```

- **Key:** the system's ID. `directory.json` uses it.
- **`name`:** the display name. Defaults to the key. It's set only here: there's no rename on
  the device.
- **`roms`:** its folder under `Roms/`. Defaults to the key. A value starting with `/` is a
  folder anywhere on the device instead, such as `"/storage/1234-ABCD/GBA"` for a folder on an SD
  card. Slashes at the end are ignored, and a value with `..` in it is skipped as an error. Cannoli
  never creates such a folder, doesn't list it under "Not in the menu", and doesn't sync saves
  for its games with RomM.
- **`extensions`:** the file types that are games, without the dot; case doesn't matter. Only
  files of these types are listed, so saves, notes and scraper files in the folder stay out.
  Archives count only when `zip` or `7z` is listed. A disc folder or multi-disc set counts when
  its `.m3u` or `.cue` is a listed type, and an `.m3u` also counts when one of its discs is.
  Without `extensions`, every file is a game. Either way, hidden files and folders (names
  starting with `.`) are skipped. An empty list is ignored, since it would hide every game. The
  templates list the types each system's RetroArch core takes, plus `zip` and `7z`, which
  RetroArch opens for any core; systems with no core have a `_note` instead, for you to fill in.
- **`romm`:** the RomM platform slug whose games are this system's, such as `"gba"`, or a list
  of them, such as `["snes", "sfc", "super-famicom"]`: the platform's folder slug in RomM
  (`fs_slug`), or its slug when it has none. Case doesn't matter. A system without `romm` gets
  nothing from RomM. A slug two systems list belongs to the first one in the file; the other is
  reported as a problem. The templates list the slugs RomM uses for each system.
- **`arcade`:** `true` for arcade romsets.
- **`emulators`:** the emulators that can run it, in order. **The first one is the default.**
  Each item is either:
  - an emulator's key, such as `"pizzaboy"`; or
  - an object with `emulator` (the key) and a value for each placeholder that emulator uses,
    such as `"core"`. The object can also take a `name`.

A system can list the same emulator more than once, for example RetroArch with two cores. Each
item is known by its `name` if it has one, or else by its emulator's key, so all but one of the
repeats need a `name`. That name is what the pickers and `overrides.json` use. Without one, the
pickers show the emulator's name and its core, as in `RetroArch · mgba_libretro`.

Values in an `emulators` object can be text, numbers or `true`/`false`. The built-in placeholder
names (`rom_path`, `package`, `rom_extension`, `rom_contents`, `rom_id`) can't be set here and
are ignored with a warning.

## `shortcuts.json`

```json
{
  "kodi-movies": {
    "name": "Movies",
    "package": "org.xbmc.kodi",
    "action": "android.intent.action.VIEW",
    "data": { "kind": "custom_scheme", "scheme": "plugin", "authority": "plugin.video.example" }
  },
  "settings-wifi": {
    "name": "Wi-Fi",
    "package": "com.android.settings",
    "action": "android.settings.WIFI_SETTINGS"
  }
}
```

- **Key:** the shortcut's name. `directory.json` uses it.
- **`name`:** shown on the menu. Defaults to the key.
- Everything else is a [launch intent](#launch-intents), with these differences, since a
  shortcut takes no game:
  - **`action`** defaults to `android.intent.action.MAIN`. With no `activity`, that opens the
    app as its home-screen icon would.
  - **`mimeType`** defaults to none.
  - **`data.kind`** can only be `none` or `custom_scheme`. A `custom_scheme` opens
    `scheme://authority`, such as `https://docs.libretro.com`.
  - **`extras`** can't use the game-file kinds (`path`, `uri_string`, `uri_parcelable`), and
    `{package}` is the only placeholder.

Shortcuts can be favorites and show in Recents, like apps.

## `directory.json`

```json
{
  "items": [
    { "folder": "Nintendo", "items": [
      { "system": "GB" },
      { "system": "GBA" }
    ] },
    { "folder": "Media", "items": [
      { "shortcut": "kodi-movies" },
      { "app": "org.xbmc.kodi" }
    ] },
    { "app": "com.android.settings", "name": "Settings" }
  ]
}
```

`items` is the main menu, in order. Recents, Favorites and Collections stay at the top of the
main menu; the directory's items come after them. Each item has exactly one of these keys:

- **`folder`:** a name, plus its own `items`, nested as deep as you like. Two folders in the
  same place can't share a name.
- **`system`:** a key from `systems.json`. It shows only once it has games. A system goes in
  one place only: if it appears again anywhere in the file, the later one is skipped.
- **`shortcut`:** a key from `shortcuts.json`.
- **`app`:** a package name, opened as its home-screen icon would be. It can take a `name`;
  without one, Cannoli uses the app's own label.

The same app or shortcut can be in several folders, but not twice in one.

Inside a folder, the screen title shows where you are, for example `Nintendo / Hacks`. Folder
artwork goes in `Art/Folders/`, nested the same way: `Art/Folders/Nintendo.png` is the art for
the top-level `Nintendo` folder, and `Art/Folders/Nintendo/Hacks.png` for `Hacks` inside it.

## `overrides.json`

```json
{
  "games": {
    "GBA/Pokemon Emerald.gba": "pizzaboy",
    "GBA/Golden Sun.gba": "gpSP"
  }
}
```

- **Key:** the game's path under `Roms/`, starting with its system's ROM folder. For a system
  whose `roms` starts with `/`, it's the game's full path instead, such as
  `"/storage/1234-ABCD/GBA/Golden Sun.gba"`.
- **Value:** one item from that system's `emulators` list: its `name` if it has one, or else its
  emulator's key.

A game launches with its override, or else with its system's first emulator. An override whose
value no longer matches anything in its system's list is skipped, and the game uses the
default. "Launch With" writes this file; you rarely need to edit it.

## Launch intents

Emulators and shortcuts describe an Android intent with these fields:

- **`package`:** required.
- **`activity`:** the activity to start. Without one, the intent goes to the package.
- **`action`:** defaults to `android.intent.action.VIEW` (`MAIN` for shortcuts).
- **`mimeType`:** sent with the data URI. Defaults to `*/*` (none for shortcuts); `null` sends
  none.
- **`intentFlags`:** the flags as an integer. Defaults to `FLAG_ACTIVITY_NEW_TASK` (268435456).
- **`launchMethod`:** `intent` (the default) or `shell`, which starts it with `am start`.
- **`data`:** how the game is passed as the intent's data URI, as `{ "kind": ... }`:
  - `none`: no data (the default when `data` is left out);
  - `file_provider`: a `content://` URI from Cannoli's file provider. It takes
    `"grantPermission"`, `true` by default, to grant the emulator read access;
  - `absolute_path`: a `file://` URI;
  - `external_storage_saf`: a storage-access-framework document URI for the file;
  - `custom_scheme`: `scheme://authority/<file path>`, which needs `scheme` and `authority`.
- **`extras`:** a list of `{ "key", "kind", ... }`. `kind` is one of:
  - `path`: the game file's absolute path, as text;
  - `uri_string`: a `content://` URI for the game, as text;
  - `uri_parcelable`: the same URI, as a `Uri`;
  - `string`, `int` or `bool`: takes a `value`, which can contain placeholders;
  - `string_array`: takes `values`, a list of text, which can contain placeholders.

  An extra can also have:
  - **`whenExtension`:** a list of file extensions. The extra is sent only for games with one of
    them.
  - **`map`:** a lookup table applied to the value after placeholders are filled in. A value the
    table has no entry for drops the extra.

  A `string`, `int` or `bool` extra whose value comes out empty isn't sent.

### Placeholders

Placeholders are written `{name}` in extra values. These are filled in for every game:

| Placeholder | Becomes |
| --- | --- |
| `{rom_path}` | the game file's absolute path |
| `{package}` | the emulator's package |
| `{rom_extension}` | the file's extension, in lower case |
| `{rom_contents}` | the first non-blank line of the file |
| `{rom_id}` | the same line without a `[tag]` prefix, so `[steamappid] 570` gives `570` |

Any other placeholder, such as `{core}`, is filled from the system's item for that emulator: a
system listing `{ "emulator": "retroarch", "core": "mgba_libretro" }` fills `{core}` with
`mgba_libretro`. A system item that leaves out a placeholder its emulator uses is skipped.

## Editing on the device

Folder editing and the emulator pickers write the same files. Each change is saved atomically,
and the previous file is kept as `<name>.bak`, for example `directory.json.bak`. Edits change
only what they need: comment keys and entries this build skipped are kept. The file is written
back with two-space indentation, so your own spacing isn't kept.

- **New folder:** press X on the main menu or in a folder.
- **START on a folder:** Rename, Add To This Folder, Move To Folder, New Folder or Delete.
  Deleting a folder moves what it held up a level. Renaming or moving a folder moves its art.
- **START on a system:** Default Emulator, Move To Folder, Remove From Folder and New Folder.
  Removing a system takes it off the menu; it then shows under Not in the menu.
- **START on an app or shortcut:** Move To Folder or Remove From Folder.
- **START on a game:** Launch With, among the usual entries.
- **Add To This Folder:** a checklist of systems that aren't placed, then installed apps.
  Unticking an app removes it from that folder.
- **Reorder:** press SELECT, move with the D-pad, then press SELECT again to save. This works on
  the main menu and in folders.

### Not in the menu

The menu shows only what `directory.json` places. While you edit, Cannoli lists what's left
out, dimmed, under a **Not in the menu** heading at the end of every START menu on the main
menu and in folders, and at the end of the list in reorder mode:

- a system from `systems.json` with games that isn't placed, as in
  `Game Boy Advance · 214 games`. Picking it asks which folder to put it in, with Main Menu
  first;
- a folder under `Roms/` with files that no system uses, as in `Roms/Foo · 12 files`. Picking
  it offers to copy a system from the templates, as described in
  [Getting started](#getting-started).

A system whose default emulator can't launch says so at the end of its row:
`· Not Installed` when the emulator's app isn't installed, or `· Core Missing` when the emulator
reports it doesn't have the system's core. The Default Emulator row in a system's START menu
shows the same mark.

The main menu doesn't list these, but its bottom bar shows how many there are, as in
`EDIT · 3 UNPLACED`.

### Launch With and Default Emulator

- **Launch With** (a game's START menu, wherever the game is shown) lists its system's
  emulators, with the first marked Default and the current one selected. Picking one saves it
  for that game in `overrides.json`. Picking the default removes the game's entry.
- **Default Emulator** (a system's START menu) shows the same list. Picking one moves that item,
  as written, to the top of the system's `emulators` list in `systems.json`, which makes it the
  default for every game without its own override.

In both lists, an emulator whose app isn't installed is marked Not Installed, and one that
reports it lacks the system's core is marked Core Missing.

## Checks before a launch

- **Is the emulator installed?** If not, Cannoli says so instead of launching. The same goes for
  apps and shortcuts.
- **Is the core installed?** Cannoli asks each emulator in `emulators.json` that has a receiver
  for the `com.retroarch.QUERY_INSTALLED_CORES` broadcast which cores it has. RetroArch answers
  it through its `InstalledCoresReceiver`; nothing else about RetroArch is built in. The answer
  is checked against the system's `core` value, so `mgba_libretro` matches
  `mgba_libretro_android.so`. If the emulator says the core is missing, Cannoli shows which core
  and doesn't launch. An emulator that has no receiver, or doesn't answer, is never blocked: its
  cores count as unknown, not missing. The query runs each time you return to Cannoli.

## Errors

- **A bad entry is skipped**, and everything else in the file still works. Cannoli shows a short
  message ("Config has 2 problems, see Logs/error.log") and writes each problem to
  `Logs/error.log`.
- **A name that refers to something missing** is a bad entry too: a system listing an emulator
  that isn't in `emulators.json`, a directory item naming a system or shortcut that isn't
  defined, or an override for a game no system's ROM folder holds.
- **A file that isn't valid JSON** is ignored: Cannoli keeps what it last read from that file,
  and never overwrites it. On-device edits to that file are refused until you fix it.
- **Device edits keep a backup:** the file before each change is saved as `<name>.bak`.

## Kitchen

Kitchen serves the config files through its API, with the same PIN as its other routes:

- `GET /api/config` lists the files: the five real files and the three templates.
- `GET /api/config/<name>` returns `{ "name", "text", "problems" }`. `problems` lists the
  problems found in that file at its last read, followed by those between files.
- `PUT /api/config/<name>`, with the whole file as the body, saves it and returns
  `{ "saved", "problems" }`. Text that isn't valid JSON is refused with status 422, and the
  file is left as it was. A file that saves can still have problems: its bad entries are
  skipped as usual.
- The templates are read-only: a `PUT` to one returns status 405.

A saved file takes effect at once: the launcher reloads its menu as it does when you return to
it. Only the files listed are reachable, by name.

Kitchen doesn't have a page for these files yet. Meanwhile, its Files tool can download and
upload them.
