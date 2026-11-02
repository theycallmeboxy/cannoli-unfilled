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
- **Unused strings:** 136 strings for the removed features, in every language.

### Changed

- **App ID:** installs as `io.github.theycallmeboxy.cannoli`, named "Cannoli Unfilled", so it sits
  alongside upstream Cannoli instead of replacing it.
- **Launch intents:** read from `Config/emulators.json` instead of being built into the app. This
  includes the RetroArch intent, which is the `retroarch` template. The file is reloaded when you
  return to Cannoli. The default RetroArch package is `com.retroarch.aarch64`.
- **Old settings:** a game or platform set to the old "Internal" runner now uses RetroArch.

### Added

- **Folders:** `Config/folders.json` holds folders of platforms, apps and other folders, nested
  as deep as you like. Folders show their path as the screen title and can have art in
  `Art/Folders/`.
- **Folder editing on the device:** new, rename, delete, move, add apps, and reorder, all saved to
  `folders.json`.
- **Kitchen config API:** `/api/config` reads and saves `folders.json` and `emulators.json`.
- **Build container:** `.boxen/Dockerfile` has JDK 21 and the Android SDK.

## Building

```
./gradlew detekt testDebugUnitTest assembleDebug lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Lint reports 46 errors that come
from upstream (missing translations, permissions); none are from this fork.

## Changelog

Newest first. Each entry is one commit on `unfilled`.

| Commit | Change |
| --- | --- |
| `01c08196` | Folder editing on the device; Kitchen config API |
| `8c41bdb7` | Drop strings left unused by the removals |
| `7f8514a9` | Drop the updater; install under the fork's own app ID |
| `2e0ab83d` | Folder path as the screen title; folder art |
| `ff23acec` | Launch intents in `emulators.json`; Tools/Ports become nested folders |
| `9a5fe01d` | Remove the built-in runner, RetroAchievements, Ricotta and Delfino |
| `f1f19b74` | Build container with JDK 21 and the Android SDK |

The fork starts from upstream commit `7755e376`.

## Status and to-do

- [ ] **Test on a device:** nothing has been tried on hardware yet. Check RetroArch and standalone
      launches, editing and breaking `emulators.json`, nested folders, and Tools/Ports carrying
      over with their favorites.
- [ ] **Kitchen page for config files:** needs a fork of CannoliHQ/kitchen, bun in the build
      container, and `release.yml` pointed at the fork. For now, use Kitchen's Files tool.
- [ ] **Local builds include no Kitchen web assets:** only CI adds them.
- [ ] **Leftover Tools/Ports names:** Kitchen's `/api/apps` and settings response, and app origin
      labels in global search.
- [ ] **Folder art is left behind** when a folder is deleted.
- [ ] **Upstream lint errors:** 46 of them.
