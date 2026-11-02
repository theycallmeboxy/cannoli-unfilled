# Configuration files

> **Out of date.** This page describes the old format. Launching now reads `emulators.json` and
> `systems.json` in the new format described in [docs/config-design.md](docs/config-design.md),
> plus `overrides.json`; `cores.json`, `platforms.ini` and the old `emulators.json` layout are no
> longer read. The launch intent fields below are unchanged. This page is being rewritten.

This fork has no built-in emulator. Every game launches in RetroArch or a standalone emulator,
and both the launch intents and the main menu's folders are plain JSON files you can edit.
Both live in `Config/` under the Cannoli folder. Cannoli re-reads them each time you return
to it, so you don't need to restart.

If a file has mistakes, the bad entries are skipped. Cannoli shows a short message and writes
the details to `Logs/error.log`.

## `Config/emulators.json`: platforms and launch intents

The first run creates this file from the built-in list. From then on, the file is the full
list: a platform you delete from it is gone from Cannoli.

```json
{
  "apps": {
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
  "GBA": {
    "name": "Game Boy Advance",
    "core": "mgba_libretro",
    "app": [
      { "package": "com.fastemulator.gba", "action": "android.intent.action.VIEW", "data": { "kind": "file_provider" } },
      "it.dbtecno.pizzaboygba"
    ]
  }
}
```

### Platforms

- **Keys:** each upper-case key is a platform tag, matching a folder under `Roms/`.
- **`name`:** the platform's display name.
- **`core`:** the default RetroArch core.
- **`arcade`:** set it to `true` for arcade romsets.
- **`app`:** the standalone emulators, in order of preference. Each entry is one of:
  - a package name;
  - the name of a template from `apps`;
  - a full intent object.

### Templates (`apps`)

`apps` holds reusable intents. The one named `retroarch` is how every RetroArch launch is
built. The package you pick under Settings → Emulation replaces its `package`.

### Intent object fields

- **`package`:** required.
- **`activity`:** the activity to start.
- **`action`:** defaults to `android.intent.action.VIEW`.
- **`mimeType`:** defaults to `*/*`; `null` sends none.
- **`intentFlags`:** the flags as an integer.
- **`launchMethod`:** `intent` (the default) or `shell`.
- **`data.kind`:** how the game is passed as the intent's data. One of:
  - `none`
  - `file_provider`
  - `absolute_path`
  - `external_storage_saf`
  - `custom_scheme`, which also needs `scheme` and `authority`
- **`extras`:** a list of `{ "key", "kind", ... }`. `kind` is one of:
  - `path`, `uri_string` or `uri_parcelable`: the game file;
  - `string`, `int` or `bool`, which take a `value`;
  - `string_array`, which takes `values`.

  An extra can also have `map`, a lookup table applied to the resolved value, and
  `whenExtension`, a list of file extensions it applies to.

### Placeholders in extra values

| Placeholder | Becomes |
| --- | --- |
| `{rom_path}` | the game file's absolute path |
| `{package}` | the package being launched |
| `{core}` | the platform's chosen core |
| `{rom_extension}` | the file's extension |
| `{rom_contents}` | the first line of the file |
| `{rom_id}` | the first line of the file, without a `[tag]` prefix |

`Config/platforms.ini` still works for renaming a platform (`[platforms]`) or changing its
default core (`[cores]`). A value in it wins over `emulators.json`.

## `Config/folders.json`: main menu folders

Folders hold platforms, apps and other folders, nested as deep as you like. Each shows its
contents in the order the file lists them. Top-level folders appear on the main menu next to
the platforms. A platform placed in any folder no longer appears at the top level.

You can edit folders on the device (see below) or in this file. Both change the same file.

```json
{
  "folders": [
    { "name": "Tools", "items": [
        { "app": "com.android.settings" },
        { "app": { "package": "org.example", "activity": "org.example.Main" }, "name": "Example" }
    ] },
    { "name": "Nintendo", "items": [
        { "platform": "GB" },
        { "platform": "GBA" },
        { "folder": { "name": "Hacks", "items": [ { "platform": "GBAHACKS" } ] } }
    ] }
  ]
}
```

### Item types

- **`folder`:** an object with a `name` and its own `items`. Two folders at the same level
  can't share a name.
- **`platform`:** a platform tag. A platform with no games stays hidden.
- **`app`:** one of:
  - a package name, which opens the app as its home-screen icon would;
  - an intent object, which can name an `activity`, `action` and fixed-value `extras`.

  Either form can take a `name`. Without one, Cannoli uses the app's own label.

The top-level `folders` list holds folders, written as `{ "name", "items" }`. It can also hold
apps, which then show on the main menu itself. Platforms don't go at the top level: a platform
that isn't in any folder is on the main menu already.

Inside a folder, the screen title shows where you are, for example `Nintendo / Hacks`.

Folder artwork goes in `Art/Folders/`, nested the same way as the folders. For example,
`Art/Folders/Nintendo.png` is the art for a top-level `Nintendo` folder, and
`Art/Folders/Nintendo/Hacks.png` is the art for `Hacks` inside it.

### Editing on the device

- **New folder:** press X on the main menu or inside a folder. Inside an empty folder, START
  also offers New Folder and Add Apps.
- **START on a folder:** Rename, Add Apps, Move To Folder, New Folder or Delete. Deleting a
  folder moves everything in it up a level, so nothing is lost.
- **START on a platform:** Rename or Move To Folder. Choose Main Menu as the target to take it
  out of its folder.
- **START on an app:** Move To Folder or Remove From Folder.
- **Add Apps:** a checklist of installed apps. Unticking an app removes it from that folder.
- **Reorder:** press SELECT, move with the D-pad, then press SELECT again to save. This works
  on the main menu and inside folders.
- **Art follows the folder:** renaming or moving a folder moves its art too.

Every change rewrites `folders.json`. The previous version is kept as `folders.json.bak`.
Apps with a hand-written intent are written back exactly as they were.

### Editing from a browser (Kitchen)

Kitchen can read and save both files through its API:

- `GET /api/config` lists the editable files.
- `GET /api/config/folders.json` returns `{ "name", "text", "problems" }`.
- `PUT /api/config/folders.json`, with the whole file as the body, saves it and returns
  `{ "saved", "problems" }`. Text that isn't valid JSON is refused with status 422, and the
  file is left unchanged.

`emulators.json` works the same way. Kitchen doesn't have a page for these files yet.
Meanwhile, its Files tool can download and upload them.

An install that had Tools or Ports lists from before `folders.json` existed gets one folder
for each list the first time it runs. Their favorites and play history carry over.
