# Cannoli Unfilled

Cannoli Unfilled is a fork of [Cannoli](https://github.com/CannoliHQ/cannoli) that keeps the
launcher and drops the built-in emulator. Every game opens in RetroArch or a standalone
emulator. Systems, emulators and the menu are plain JSON files you can edit; see
[CONFIG.md](CONFIG.md).

All fork work lives on the `unfilled` branch. `main` tracks upstream and stays untouched.

## How it differs from upstream

### Removed

- **Built-in libretro runner:** the JNI bridge, bundled cores, the in-game menu
  (`cannoli-igm`), shaders, cheats, save states and their picker, Resume, and in-game hotkeys.
- **RetroAchievements:** emulators handle achievements themselves. Saved RA credentials are
  deleted on first start.
- **Ricotta and Delfino:** the custom launch protocol for upstream's emulator forks, the core
  download service that went with it, and the leftover Ricotta names.
- **Updater:** the app no longer checks for or installs upstream releases.
- **Tools and Ports:** replaced by folders in `directory.json` (below).
- **Content modes:** the Collections Only and Five Game Handheld main menus, with the 5GH
  collection setting.
- **Built-in platform and emulator knowledge:** the bundled `platforms.json` as live config, the
  core catalog (`core_info`), the special-case RetroArch launcher and the RetroArch package
  setting. RetroArch is now an emulator entry like any other.
- **Old config files:** `emulators.json` in its old format, `cores.json`, `folders.json`,
  `platforms.ini`, the per-game emulator choices in the database, and the `__FOLDER__/` and
  `__APP__/` order tags. Nothing is converted from them.
- **Emulator Mapping and Settings → Emulation:** the platform list with its emulator and core
  picker, BIOS status, the per-game overrides list and Reset, and the Installed Cores screen. A
  game's "Emulator Override" entry is replaced by "Launch With", and renaming a platform from
  the menu is gone: a system's name is set in `systems.json`.
- **ROM ignore lists:** `Config/ignore_extensions_roms.txt` and `Config/ignore_files_roms.txt`
  are no longer created or read. A system's `extensions` in `systems.json` decides which files
  are games; hidden files and folders are still skipped.
- **Unused strings:** 136 strings for the removed features, plus 50 for Emulator Mapping and the
  Emulation settings, in every language.

### Changed

- **App ID:** installs as `io.github.theycallmeboxy.cannoli`, named "Cannoli Unfilled", so it sits
  alongside upstream Cannoli instead of replacing it.
- **Config:** the library is five JSON files in `Config/`, each with one job, referring to each
  other by name (see [CONFIG.md](CONFIG.md) and [docs/config-design.md](docs/config-design.md)):
  - `emulators.json`: emulators, each a name and a launch intent with `{placeholders}`;
  - `systems.json`: systems, each with a name, a folder under `Roms/` (which can differ from its
    ID), file types, an arcade flag, and the emulators that run it, the first being the default;
  - `shortcuts.json`: custom intents that act like apps;
  - `directory.json`: the menu tree of folders, systems, shortcuts and apps;
  - `overrides.json`: the emulator picked for single games.

  Three `.template.json` files next to them hold every known emulator and system, and example
  shortcuts, to copy from. Keys starting with `_` are comments. The files are re-read when you
  return to Cannoli; bad entries are skipped, shown as a short message and listed in
  `Logs/error.log`; a file that isn't valid JSON keeps its last good state and isn't
  overwritten. Each problem is an error (something skipped whole) or a warning (one option or
  reference dropped, the rest works), logged once with `ERROR`/`WARNING` and its file first.
- **Empty to start:** new and existing installs start with these files empty, so the main menu
  shows no systems until you copy some in from the templates, or adopt a ROM folder on the
  device.
- **Launching:** a game launches with its entry in `overrides.json`, or else its system's first
  emulator, with the system's values (such as `"core"`) filled into the intent.
- **Main menu order:** the menu is `directory.json`, in the order it lists things, after
  Recents, Favorites and Collections. Platforms are no longer sorted by name or kept in the
  database. A system shows only where the directory places it, and only once it has games.
- **Core check:** before a launch, an emulator that answers RetroArch's installed-cores query is
  asked whether it has the game's core; if it says no, a dialog names the missing core. Any
  emulator with a receiver for the query is asked, not just RetroArch, and one that can't answer
  is never blocked.
- **Recents:** "Recently Played" is called "Recents" (in English; other languages keep their
  translation for now). Its setting has three values: Show, Games Only (no apps) and Hide.
- **Default font:** IBM Plex Mono. The old default, M+ 1 Code, is still in the font list, and
  Cannoli still falls back to it for languages Plex doesn't cover (Chinese, Japanese and Korean).
- **Quick Menu and Settings:** they're the last two rows of the main menu instead of hotkeys. The
  Menu button no longer opens the quick menu, and X/North no longer opens Settings.
- **No button hints:** the bar of button hints at the bottom of every screen is gone, and lists use
  the space. `ShowBottomBar` in `BottomBar.kt` brings it back.
- **Highlight:** Settings → Display → Highlight picks Pill (the default), Text or Bold Text. Without
  the pill, the selected row takes the Highlight color, and other rows dim when both colors match.
- **Layout:** the Center layout is the default: list rows centered, the screen title centered, the
  clock at the top left and the other status icons at the top right. Settings → Display → Layout
  switches back to Left, upstream's layout.
- **RomM platforms:** a RomM platform belongs to the system whose `romm` in `systems.json` lists
  its slug, instead of a built-in slug list, so a system with an ID of your own can match too.

### Added

- **Folders:** folders of systems, shortcuts, apps and other folders, nested as deep as you
  like. They show their path as the screen title and can have art in `Art/Folders/`.
- **Shortcuts:** an entry from `shortcuts.json` placed in the directory opens its own intent,
  such as a Kodi add-on, like an app. Shortcuts can be favorites and show in Recents.
- **Folder editing on the device:** new, rename, delete, move, remove, "Add To This Folder" and
  reorder, on the main menu and in folders, all saved to `directory.json`. Systems can be moved
  and removed like folders and apps.
- **Not in the menu:** a system with games that the directory doesn't place, or a folder under
  `Roms/` with files that no system uses, isn't shown on the menu. While editing, it's listed,
  dimmed, at the end of every START menu and in reorder mode. Picking a system places it in a
  folder you choose; picking a ROM folder offers to copy its system, and the emulators that
  system uses, from the templates. "Add To This Folder" lists these systems above the apps, and
  the main menu's bottom bar shows how many there are (`EDIT · 3 UNPLACED`). A system whose
  default emulator isn't installed, or reports the core missing, is marked so.
- **Launch With:** a game's START menu, wherever it's shown, lists its system's emulators, with
  the first marked Default and missing apps and cores marked. Picking one saves it for that game
  in `overrides.json`; picking the default removes the game's entry.
- **Default Emulator:** a system's START menu shows the same list, and the row itself is marked
  when the default's app or core is missing. Picking one moves it to the top of the system's
  `emulators` list in `systems.json`.
- **Safe device edits:** every edit goes through the file's JSON, keeps comment keys and entries
  this build skipped, writes atomically and keeps the previous file as `<name>.bak`.
- **Kitchen config API:** `/api/config` lists, reads and saves the five config files, and serves
  the three templates read-only. A saved file is checked first (invalid JSON is refused with
  422), and the launcher reloads its menu at once.
- **ROM folders anywhere:** a system's `roms` in `systems.json` can be a full path, such as a
  folder on an SD card, instead of a folder under `Roms/`. Its games are scanned, watched and
  launched like any other; save sync leaves them out.
- **Build container:** `.boxen/Dockerfile` has JDK 21 and the Android SDK.

## Kitchen

Kitchen's web page lives in `kitchen/`, copied from
[CannoliHQ/kitchen](https://github.com/CannoliHQ/kitchen) at `efa67ff` (2026-09-06) and edited here
from then on; both projects are GPL-3.0. The app build compiles it into
`app/src/main/assets/kitchen` with bun (`scripts/build-kitchen.sh`, run by Gradle before each
build). A build without bun on the PATH skips it and ships no page.

This fork doesn't follow upstream Cannoli or Kitchen any more. What differs from upstream's page:

- **Config Files** (Tools → Config Files): lists the five config files and the three templates.
  A file opens in a CodeMirror editor that completes keys and values from the file formats, the
  other files, the templates and the device (`GET /api/config/hints`), and underlines problems as
  you type. It checks the JSON before saving and points at the error, then shows the problems
  Cannoli found in it and between files. Revert, reload, and a warning before leaving with unsaved
  changes. A template opens read-only with a list of its entries: tick some and they're copied
  into the real file, never replacing an entry it already has. Systems also bring the emulators
  they use that `emulators.json` lacks, and new systems and shortcuts can be placed at the end of
  the main menu.
- **Files:** the Cannoli folder is listed first, before Internal Storage and SD cards. Downloads
  go straight to disk through a plain link instead of through the page's memory, so large files
  work on a phone. Errors from upload, new folder, rename, move and delete are shown, and one
  failed file no longer stops a batch upload. Renaming a file to the same name in different case
  works on storage that ignores case.
- **APK Installer:** checks whether Cannoli may install apps ("Install unknown apps"), says how
  to allow it, and can open that switch on the device. Clearer messages when Android refuses an
  install, a way to stop waiting for a dismissed prompt, and the staged APK is deleted as soon as
  Android has its copy.
- **Controller Mappings:** the server now has the `/api/mappings` endpoint the page called.
- **Removed:** the save state, cheat, overlay, shader and FBNeo samples pages, the RetroAchievements
  ID in game data, and the server's `/api/slots`, `/api/artwork`, `/api/scan` and `/api/settings`.
  Apps are called Apps; apps older builds filed under Ports are listed as "Apps (old Ports)", since
  their box art is still in `Art/PORTS/`.

## Building

```
./gradlew detekt testDebugUnitTest assembleDebug lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Lint reports 46 errors that come
from upstream (missing translations, permissions); none are from this fork.

## Changelog

Newest first. Each entry is one commit on `unfilled`; `git log` has the hashes.

| Date | Change |
| --- | --- |
| 2026-11-03 | Bottom button-hint bar removed |
| 2026-11-03 | Config problems labelled error or warning, logged once per load |
| 2026-11-03 | Bold Text uses a real bold face; secondary text keeps its color |
| 2026-11-03 | Kitchen: IDE-style config editor with autocomplete from the device and other files |
| 2026-11-03 | Kitchen: config file editor, working file transfer and APK install; removed dead pages |
| 2026-11-03 | Kitchen web page source copied into kitchen/ and built with the app |
| 2026-11-03 | RomM platform set per system; extensions decide which files are games |
| 2026-11-03 | Quick Menu and Settings are main menu rows, not hotkeys |
| 2026-11-02 | Absolute ROM folders for systems |
| 2026-11-02 | Highlight setting: Pill, Text or Bold Text |
| 2026-11-02 | Kitchen serves the new config files; CONFIG.md rewritten |
| 2026-11-02 | Launch With and Default Emulator pickers |
| 2026-11-02 | Menu and folders come from directory.json; shortcuts; unplaced systems while editing |
| 2026-11-02 | Systems and emulators come from systems.json and emulators.json; emulator mapping removed |
| 2026-11-02 | Config model and templates for the new config files |
| 2026-11-02 | Center layout: centered title, clock at the top left |
| 2026-11-02 | Recents setting (Show / Games Only / Hide); centered layout; IBM Plex Mono default |
| 2026-11-02 | Remove content modes; rename Recently Played to Recent; Apps In Recent setting |
| 2026-11-02 | Add this file |
| 2026-11-01 | Folder editing on the device; Kitchen config API |
| 2026-11-01 | Drop strings left unused by the removals |
| 2026-11-01 | Drop the updater; install under the fork's own app ID |
| 2026-11-01 | Folder path as the screen title; folder art |
| 2026-11-01 | Launch intents in `emulators.json`; Tools/Ports become nested folders |
| 2026-11-01 | Remove the built-in runner, RetroAchievements, Ricotta and Delfino |
| 2026-11-01 | Build container with JDK 21 and the Android SDK |

The fork starts from upstream commit `7755e376`.

## Status and to-do

- [ ] **Test on a device:** the app installs and opens on a Pocket Block (Android 12); nothing
      has been launched from it yet. Check RetroArch and standalone launches, editing and
      breaking each config file, nested folders, shortcuts, placing systems and adopting ROM
      folders from "Not in the menu", "Launch With" and "Default Emulator" with their marks, and
      Kitchen's Config Files page, file transfers (large and many files, from a phone too) and
      APK installs (with and without "Install unknown apps", and with Cannoli in the background).
- [ ] **Leftover Tools/Ports names:** app origin labels in global search, and Kitchen's
      `/api/apps`, which still answers in two lists because app art is in `Art/TOOLS/` and
      `Art/PORTS/`.
- [ ] **Save sync for renamed ROM folders:** a game RomM hasn't linked yet is matched by its ROM
      folder name, so for a system whose `roms` differs from its ID only linked games sync.
- [ ] **Folder art is left behind** when a folder is deleted.
- [ ] **Upstream lint errors:** 46 of them.
