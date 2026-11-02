# Design: config files

Status: **proposal**, not built yet.

## Why

Today the library is described in three files and a database table, and they overlap:

- **`Config/emulators.json`:** platforms, their RetroArch core, and standalone intents.
- **`Config/folders.json`:** the menu tree.
- **`Config/cores.json`:** the emulator and core picked for each platform.
- **The `game_overrides` table:** the emulator picked for single games.

On top of these, the app has its own RetroArch logic: known packages, a built-in core catalog, and
installed-core checks.

This proposal splits the config into four files that each do one job and refer to each other by
name. RetroArch is just an emulator entry like any other, and the app has no special knowledge of
it.

## The files

| File | Holds | Refers to |
| --- | --- | --- |
| `Config/emulators.json` | Emulators: a name plus a launch intent | nothing |
| `Config/systems.json` | Game systems: a ROM folder, file types, and which emulators run it | `emulators.json` |
| `Config/shortcuts.json` | Custom launch intents that act like an app rather than a system | nothing |
| `Config/directory.json` | The menu tree: folders, apps, shortcuts and systems | `systems.json`, `shortcuts.json` |
| `Config/overrides.json` | The emulator chosen for single games. Only the device writes it. | `emulators.json` |
| `Config/emulators.template.json` | An explanation plus every known emulator example | reference only |
| `Config/systems.template.json` | Every known system, with its default emulators | reference only |
| `Config/shortcuts.template.json` | An explanation plus custom intent examples | reference only |

Cannoli rewrites the `.template.json` files on every update and never reads them as config. To
use an entry, copy it into the real file.

## Defaults

A new install starts with every real file empty. With nothing defined, the main menu is empty.
The templates sit next to the real files, ready to copy from.

## `emulators.json`

```json
{
  "retroarch": {
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

- **Key:** the emulator's name, which the other files use to refer to it.
- **Value:** a launch intent, with the same fields as today (see `CONFIG.md`), plus an optional
  display `name`.
- **Placeholders:** any value can contain one. These are always filled in: `{rom_path}`,
  `{package}`, `{rom_extension}`, `{rom_contents}` and `{rom_id}`. Any other placeholder, such as
  `{core}`, is filled from the system that uses the emulator. That's how one `retroarch` entry
  serves every core.

## `systems.json`

```json
{
  "GBA": {
    "name": "Game Boy Advance",
    "roms": "GBA",
    "extensions": ["gba", "zip", "7z"],
    "emulators": [
      { "emulator": "retroarch", "core": "mgba_libretro" },
      "pizzaboy"
    ]
  }
}
```

- **Key:** the system's ID, which `directory.json` refers to.
- **`name`:** the display name.
- **`roms`:** its folder under `Roms/`. Defaults to the ID.
- **`extensions`:** the file types it accepts. Without it, every file is accepted except the global
  ignore list, as today.
- **`arcade`:** `true` for arcade romsets.
- **`emulators`:** the emulators that can run it, in order. **The first one is the default.** An
  entry is either:
  - an emulator's name, such as `"pizzaboy"`; or
  - an object with `emulator` (the name) plus values for that emulator's placeholders, such as
    `"core"`. The object can also take a `name` for the picker.

## `shortcuts.json`

```json
{
  "kodi-movies": {
    "name": "Movies",
    "package": "org.xbmc.kodi",
    "action": "android.intent.action.VIEW",
    "data": { "kind": "custom_scheme", "scheme": "plugin", "authority": "plugin.video.example" }
  }
}
```

- **Key:** the shortcut's name, which `directory.json` refers to.
- **Value:** a `name` and a launch intent. A shortcut takes no ROM, so the ROM placeholders don't
  apply.

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

`items` is the main menu, in order. An item is one of four kinds:

- **`folder`:** a name and its own `items`, nested to any depth.
- **`system`:** an ID from `systems.json`. It shows only if it has games, as today.
- **`shortcut`:** a name from `shortcuts.json`.
- **`app`:** a package name, opened as its home-screen icon would be. It can take a `name`.

A system can appear in only one place. If it appears twice, the second is skipped with a warning.

### Systems that aren't placed

A system that's defined in `systems.json` and has games, but isn't in `directory.json`, doesn't
appear in the normal menu. The directory stays the single source of truth. Cannoli points such
systems out whenever you're editing, so they're never lost:

- **While editing (reorder mode, or any folder menu):** the end of the list shows a dimmed section
  headed **Not in the menu**. It lists each unplaced system with its game count, for example
  `Game Boy Advance · 214 games`. Selecting one opens the folder picker ("Place in…", with
  Main Menu as the first target) and adds the system there.
- **In "Add To This Folder":** the checklist that adds apps also lists unplaced systems at the
  top, with their game counts, so one picker fills a folder with both.
- **On the main menu itself:** nothing appears, to avoid clutter. The bottom bar's edit hint
  shows a count when anything is unplaced (for example `EDIT · 3 UNPLACED`), so you know to look.

Two related cases come up in the same section:
- **ROM folders with no system:** a folder under `Roms/` that holds files but matches no system's
  `roms`. These are listed by folder name and file count, and selecting one offers to copy a
  matching system from `systems.template.json` if one exists.
- **Systems with no games:** these are not listed, since there's nothing to play. They stay
  quietly defined until their folder fills up.

## `overrides.json`

```json
{
  "games": {
    "GBA/Pokemon Emerald.gba": "pizzaboy"
  }
}
```

The key is the ROM's path under `Roms/`. The value names one entry from that system's `emulators`
list: either its emulator name, or its own `name` when the same emulator appears twice with
different cores. If the name no longer matches any entry, the game falls back to the system's
default.

## The template files

JSON has no comments, so explanations go in keys that start with `_`. Cannoli ignores any key
starting with `_` in every file, so an entry can carry a `_note` once it's copied out of a
template.

```json
{
  "_about": [
    "Every emulator Cannoli knows how to launch. Copy the ones you use into emulators.json.",
    "Placeholders: {rom_path}, {package}, {rom_extension}, {rom_contents}, {rom_id}; others come from systems.json."
  ],
  "retroarch": { "_note": "RetroArch 64-bit. Systems pass {core}, e.g. mgba_libretro.", "package": "com.retroarch.aarch64", "...": "..." },
  "pizzaboy": { "_note": "Pizza Boy GBA (free).", "package": "it.dbtecno.pizzaboygba", "...": "..." }
}
```

- **`emulators.template.json`:** one entry for each emulator in today's built-in list. That's
  RetroArch plus the standalone emulators from `assets/platforms.json`, which has about 60 systems'
  worth.
- **`shortcuts.template.json`:** a handful of worked examples covering the different ways to pass
  data: an activity with fixed extras, a `VIEW` with a URI, and a custom scheme.

- **`systems.template.json`:** every system in today's built-in list, with its name, ROM folder
  and emulator list, all referring to entries in `emulators.template.json`. Copying a system plus
  the emulators it names gives a working setup.

## On the device

- **A game's context menu:** "Launch With" lists the system's emulators, marks the ones whose app
  isn't installed, and saves the choice to `overrides.json`.
- **A system's context menu:** "Default Emulator" moves the chosen one to the top of its list in
  `systems.json`.
- **Folder editing** (new, rename, move, delete, reorder, add apps) works as it does now, and
  writes `directory.json`.
- **The Settings → Emulation screen goes away.** That includes Emulator Mapping, the RetroArch
  package setting, and the core picker.

## What goes away

- `folders.json` and `cores.json`. `emulators.json` keeps its name but changes format.
- The bundled `platforms.json` as live config. It becomes the source for the templates.
- `EmulatorSource`, the core picker and core info catalog, the RetroArch package setting, the
  special-case RetroArch launcher, and the leftover Ricotta names. The installed-cores query
  stays (see "Checking launches on the device").
- Per-game emulator choices in the database, which move to `overrides.json`.
- The `__FOLDER__/` and `__APP__/` order tags. The order of `items` is the menu order.

## Checking launches on the device

Cannoli checks what it can before a launch fails:

- **Is the app installed?** This is checked for every emulator, shortcut and app. A missing app is
  marked in the pickers, and launching it shows the existing "app not installed" dialog.
- **Is the core installed?** RetroArch answers a broadcast,
  `com.retroarch.QUERY_INSTALLED_CORES`, with the cores it has (RetroArch's
  `InstalledCoresReceiver`). Upstream Cannoli already has this code (`InstalledCoreService`), so
  it's kept, but made generic:
  - It works for any emulator whose package answers the broadcast. Whether a package does is
    found by asking Android whether it has a receiver for it, so nothing is hard-coded about
    RetroArch.
  - The emulator's value for the core comes from the system entry (`"core": "mgba_libretro"`),
    and that's what's checked.
  - A system whose default core is missing is marked in the editing views, and "Launch With"
    marks each missing core.
  - A package that doesn't answer is treated as unknown, never as missing.
- **Does the config make sense?** References that don't resolve are reported as described under
  Errors.

## Errors

These work as today:
- A bad entry is skipped, a short on-screen message appears, and the details go to
  `Logs/error.log`.
- A file that isn't valid JSON is ignored, the menu keeps its last good state, and the file is
  never overwritten.
- A name that refers to something missing is a bad entry. Examples are a system listing an emulator
  that isn't in `emulators.json`, or the directory listing a system that isn't in `systems.json`.
- Device edits write atomically and keep a `.bak` copy.

## Kitchen

`/api/config` serves the four real files plus `overrides.json`.

## Open questions

1. **Existing installs:** should the first run convert today's files and per-game choices into the
   new ones, or start empty? The only install so far is the test device.
2. **System IDs and RomM:** RomM matching uses Cannoli's built-in IDs (`GBA`, `PS`…). A
   made-up ID would simply get no RomM match.
