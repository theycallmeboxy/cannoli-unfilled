# Design: one library file

Status: **proposal**, not built yet.

## Why

Today three files and a database table describe the same platforms:

- **`Config/emulators.json`:** platforms, their RetroArch core, and standalone emulator intents.
- **`Config/folders.json`:** where platforms and apps sit in the menu.
- **`Config/cores.json`:** the emulator and core picked for each platform.
- **The `game_overrides` table:** the emulator picked for single games.

On top of them, the app has its own RetroArch logic. It knows the RetroArch packages, keeps a
built-in catalog of cores, and checks which cores are installed. A platform is named in two files,
and RetroArch is treated as a special case.

This proposal replaces all of it with **one file that describes the whole library**, plus one
small file the device writes to. RetroArch becomes an ordinary launcher like any other app.

## The files

| File | Who writes it | Holds |
| --- | --- | --- |
| `Config/library.json` | You (by hand, Kitchen, or on-device editing) | Templates, folders, platforms, apps, and the launchers for each platform |
| `Config/overrides.json` | The device | The launcher chosen for single games |
| `Config/library.template.json` | Cannoli (rewritten on each update) | A complete example covering every platform Cannoli knows. It's for reference only and is never read as config. |

## Defaults

A new install gets an **empty** `library.json`, so the main menu is empty until you add
something. Cannoli ships `library.template.json` next to it, and you can copy what you want from
there. Later, Kitchen or the device could offer "add from template".

## `library.json`

```json
{
  "templates": {
    "retroarch": {
      "package": "com.retroarch.aarch64",
      "activity": "com.retroarch.browser.retroactivity.RetroActivityFuture",
      "action": "android.intent.action.MAIN",
      "extras": [
        { "key": "ROM", "kind": "string", "value": "{rom_path}" },
        { "key": "LIBRETRO", "kind": "string", "value": "/data/data/{package}/cores/{core}_android.so" },
        { "key": "CONFIGFILE", "kind": "string", "value": "/storage/emulated/0/Android/data/{package}/files/retroarch.cfg" }
      ]
    }
  },
  "items": [
    { "folder": "Nintendo", "items": [
      { "platform": "GBA",
        "name": "Game Boy Advance",
        "roms": "GBA",
        "extensions": ["gba", "zip", "7z"],
        "launchers": [
          { "name": "mGBA", "use": "retroarch", "core": "mgba_libretro" },
          { "name": "Pizza Boy", "package": "it.dbtecno.pizzaboygba",
            "extras": [ { "key": "rom_uri", "kind": "uri_string" } ] }
        ] }
    ] },
    { "app": "com.android.settings", "name": "Settings" }
  ]
}
```

### `templates`

Named intents that platforms reuse. A template uses the same intent fields as today (see
`CONFIG.md`), and any value can contain `{placeholders}`.

### `items`

The main menu, in order. Each item is one of three kinds.

- **`folder`:** has a name and its own `items`, nested to any depth.
- **`app`:** a package name, or a full intent. It can take a `name`. This works the same as in
  today's `folders.json`.
- **`platform`:** a platform's ID. It also takes these fields:
  - **`name`:** the display name.
  - **`roms`:** its folder under `Roms/`. Defaults to the ID.
  - **`extensions`:** the file types it accepts. Without it, every file is accepted except the
    global ignore list, as today.
  - **`arcade`:** `true` for arcade romsets.
  - **`launchers`:** see the next section.

A platform shows only if it has games, as today. A platform that isn't in this file isn't in
Cannoli, even if its ROM folder exists.

### Launchers

A launcher is a way to start a game, and a platform lists them in order. **The first launcher is
the platform's default.** Each launcher is one of:

- **A template use:** `{ "use": "retroarch", "core": "mgba_libretro" }`. Every field other than
  `use` and `name` fills a placeholder in the template, so `core` fills `{core}`. It can also
  replace a template field outright, for example `"package": "com.retroarch"`.
- **A full intent,** written inline.

Every launcher can have a `name`, which is what the picker shows. Without one, the picker shows the
app's label, plus the core if there is one.

### Placeholders

These are filled in at launch: `{rom_path}`, `{package}`, `{rom_extension}`, `{rom_contents}` and
`{rom_id}`. Any value given on a launcher fills the placeholder of the same name, such as
`{core}`.

## `overrides.json`

```json
{
  "games": {
    "GBA/Pokemon Emerald.gba": "Pizza Boy"
  }
}
```

The key is the ROM's path under `Roms/`, and the value is the name of one of that platform's
launchers. If the name no longer matches a launcher, the game falls back to the platform default.

## On the device

- **A game's context menu:** "Launch With" lists the platform's launchers, marks those whose app
  isn't installed, and saves the choice to `overrides.json`.
- **A platform's context menu:** "Default Launcher" moves the chosen launcher to the top of the
  platform's list in `library.json`.
- **Folder editing** (new, rename, move, delete, reorder, add apps) works as it does now, but
  writes `library.json`.
- **The Settings → Emulation screen goes away.** That includes Emulator Mapping, the RetroArch
  package setting, and installed cores.

## What goes away

- `emulators.json`, `folders.json` and `cores.json`, which `library.json` replaces.
- The bundled `platforms.json` as live config. It becomes the source for `library.template.json`.
- `EmulatorSource`, the RetroArch core picker, the core info catalog, RetroArch package detection,
  and the special-case RetroArch launcher.
- Per-game emulator choices in the database, which move to `overrides.json`.
- The `__FOLDER__/` and `__APP__/` main-menu order tags. The order of `items` is the menu order.

## Errors

These work as today:
- A bad entry is skipped, a short on-screen message appears, and the details go to
  `Logs/error.log`.
- A file that isn't valid JSON leaves the menu as it last was, and is never overwritten.
- Device edits write atomically and keep a `.bak` copy.

## Kitchen

`/api/config` serves `library.json` and `overrides.json` instead of the files they replace.

## Open questions

1. **Existing installs:** should the first run convert today's `emulators.json`, `folders.json`,
   `cores.json` and per-game choices into `library.json`, or start empty like a new install? The
   only install so far is the test device.
2. **Platform IDs and RomM:** the RomM integration maps its platforms to Cannoli's built-in IDs
   (`GBA`, `PS`…). A user-defined ID that isn't in that map would simply have no RomM match. Is
   that acceptable?
3. **Recents, favorites, collections:** these refer to games by path and platform, so they keep
   working as long as a platform's `roms` folder doesn't change. Renaming the folder would orphan
   them, as it does today.
