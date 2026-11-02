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
- **Unused strings:** 136 strings for the removed features, in every language.

### Changed

- **App ID:** installs as `io.github.theycallmeboxy.cannoli`, named "Cannoli Unfilled", so it sits
  alongside upstream Cannoli instead of replacing it.
- **Launch intents:** read from `Config/emulators.json` instead of being built into the app. This
  includes the RetroArch intent, which is the `retroarch` template. The file is reloaded when you
  return to Cannoli. The default RetroArch package is `com.retroarch.aarch64`.
- **Old settings:** a game or platform set to the old "Internal" runner now uses RetroArch.
- **Recents:** "Recently Played" is called "Recents" (in English; other languages keep their
  translation for now). Its setting has three values: Show, Games Only (no apps) and Hide.
- **Default font:** IBM Plex Mono. The old default, M+ 1 Code, is still in the font list, and
  Cannoli still falls back to it for languages Plex doesn't cover (Chinese, Japanese and Korean).
- **Layout:** the Center layout is the default: list rows centered, the screen title centered, the
  clock at the top left and the other status icons at the top right. Settings → Display → Layout
  switches back to Left, upstream's layout.

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

Newest first. Each entry is one commit on `unfilled`; `git log` has the hashes.

| Date | Change |
| --- | --- |
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
      launches, editing and breaking `emulators.json`, nested folders, and Tools/Ports carrying
      over with their favorites.
- [ ] **Kitchen page for config files:** needs a fork of CannoliHQ/kitchen, bun in the build
      container, and `release.yml` pointed at the fork. For now, use Kitchen's Files tool.
- [ ] **Local builds include no Kitchen web assets:** only CI adds them.
- [ ] **Leftover Tools/Ports names:** Kitchen's `/api/apps` and settings response, and app origin
      labels in global search.
- [ ] **Folder art is left behind** when a folder is deleted.
- [ ] **Upstream lint errors:** 46 of them.
