# Cannoli Unfilled

Cannoli Unfilled is a fork of [Cannoli](https://github.com/CannoliHQ/cannoli) that keeps the
launcher and drops the built-in emulator. Every game opens in RetroArch or a standalone
emulator. Launch intents and main menu folders are plain JSON files you can edit; see
[CONFIG.md](CONFIG.md).

All fork work lives on the `unfilled` branch. `main` tracks upstream and stays untouched.

## How it differs from upstream

### Removed

- **Built-in libretro runner:** the JNI bridge, bundled cores, the in-game menu
  (`cannoli-igm`), shaders, cheats, save states and their picker, Resume, and in-game hotkeys.
- **RetroAchievements:** emulators handle achievements themselves. Saved RA credentials are
  deleted on first start.
- **Ricotta and Delfino:** the custom launch protocol for upstream's emulator forks, and the core
  download service that went with it.
- **Updater:** the app no longer checks for or installs upstream releases.
- **Tools and Ports:** replaced by user folders (below).
- **Content modes:** the Collections Only and Five Game Handheld main menus, with the 5GH
  collection setting. The main menu always shows platforms and folders.
- **Emulator Mapping and Settings → Emulation:** the platform list with its emulator and core
  picker, BIOS status, the per-game overrides list and Reset, the RetroArch package setting, and
  the Installed Cores screen. A game's "Emulator Override" menu entry is gone too, until "Launch
  With" replaces it.
- **Built-in emulator knowledge:** the core catalog (`core_info`), `cores.json`, `platforms.ini`
  renames, the special-case RetroArch launcher, and the leftover Ricotta names. Renaming a
  platform from the main menu is gone with `platforms.ini`: a system's name is set in
  `systems.json`.
- **Unused strings:** 136 strings for the removed features, plus 50 for Emulator Mapping and the
  Emulation settings, in every language.

### Changed

- **App ID:** installs as `io.github.theycallmeboxy.cannoli`, named "Cannoli Unfilled", so it sits
  alongside upstream Cannoli instead of replacing it.
- **Platforms and launching:** platforms are the systems in `Config/systems.json`, and their launch
  intents are the emulators in `Config/emulators.json` (new format; see
  [docs/config-design.md](docs/config-design.md)). A system gives its name, its folder under
  `Roms/` (which can differ from its ID), the file types it accepts, whether it's arcade, and the
  emulators that run it. A game launches with its entry in `Config/overrides.json`, or else its
  system's first emulator, with the system's values (such as `"core"`) filled into the intent.
  RetroArch is an emulator entry like any other. The files are reloaded when you return to
  Cannoli, and bad entries are skipped and listed in `Logs/error.log`.
- **Empty to start:** new and existing installs start with these files empty, so the main menu
  shows no platforms until you copy systems and emulators in from the `.template.json` files next
  to them. Nothing is converted from the old `emulators.json`, `cores.json`, `folders.json` or
  per-game choices.
- **Main menu order:** the menu is `Config/directory.json`, in the order it lists things. Recents,
  Favorites and Collections stay at the top; after them come the directory's folders, systems,
  shortcuts and apps. Platforms are no longer sorted by name or kept in the database, and
  reordering with SELECT rewrites the order in `directory.json`, on the main menu and in folders.
  A system shows only where the directory places it, and only once it has games.
- **Core check:** before a launch, an emulator that answers RetroArch's installed-cores query is
  asked whether it has the game's core; if it says no, a dialog says which core is missing. An
  emulator that can't answer is never blocked.
- **Recents:** "Recently Played" is called "Recents" (in English; other languages keep their
  translation for now). Its setting has three values: Show, Games Only (no apps) and Hide.
- **Default font:** IBM Plex Mono. The old default, M+ 1 Code, is still in the font list, and
  Cannoli still falls back to it for languages Plex doesn't cover (Chinese, Japanese and Korean).
- **Layout:** the Center layout is the default: list rows centered, the screen title centered, the
  clock at the top left and the other status icons at the top right. Settings → Display → Layout
  switches back to Left, upstream's layout.

### Added

- **Folders:** `Config/directory.json` holds folders of systems, shortcuts, apps and other folders,
  nested as deep as you like. Folders show their path as the screen title and can have art in
  `Art/Folders/`.
- **Shortcuts:** an entry from `Config/shortcuts.json` placed in the directory opens its own
  intent, such as a Kodi add-on, like an app. Shortcuts can be favorites and show in Recents.
- **Folder editing on the device:** new, rename, delete, move, remove, "Add To This Folder" and
  reorder, all saved to `directory.json` (the previous file is kept as `directory.json.bak`).
  Systems can be moved and removed like folders and apps.
- **Not in the menu:** a system with games that the directory doesn't place, or a folder under
  `Roms/` with files that no system uses, isn't shown on the menu. While editing, it's listed,
  dimmed, at the end: in reorder mode, and in every edit menu (START), where picking a system
  places it in a folder you choose. Picking such a ROM folder offers to copy its system, and the
  emulators that system uses, from the templates. "Add To This Folder" lists these systems above
  the apps. The main menu's bottom bar shows how many there are.
- **Kitchen config API:** `/api/config` serves the files it is given. It lists none for now: the
  new config files are added in a later stage.
- **Build container:** `.boxen/Dockerfile` has JDK 21 and the Android SDK.

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
      has been launched from it yet. Check RetroArch and standalone
      launches, editing and breaking `emulators.json`, nested folders, shortcuts, and placing
      systems from "Not in the menu".
- [ ] **Kitchen page for config files:** needs a fork of CannoliHQ/kitchen, bun in the build
      container, and `release.yml` pointed at the fork. For now, use Kitchen's Files tool.
- [ ] **Local builds include no Kitchen web assets:** only CI adds them.
- [ ] **Leftover Tools/Ports names:** Kitchen's `/api/apps` and settings response, and app origin
      labels in global search.
- [ ] **Folder art is left behind** when a folder is deleted.
- [ ] **Upstream lint errors:** 46 of them.
