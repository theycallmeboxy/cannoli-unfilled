/** A small set of config files, templates and device hints for the config editor's tests. */
import type { ConfigData } from '@/lib/config/configData'

/** Splits a text with one `|` into the text and the cursor's position. */
export function at(marked: string): [string, number] {
  const pos = marked.indexOf('|')
  return [marked.slice(0, pos) + marked.slice(pos + 1), pos]
}

export const DATA: ConfigData = {
  files: {
    'emulators.json': {
      retroarch: {
        name: 'RetroArch',
        package: 'com.retroarch.aarch64',
        extras: [{ key: 'LIBRETRO', kind: 'string', value: '/data/data/{package}/cores/{core}_android.so' }],
      },
      pizzaboy: { name: 'Pizza Boy GBA', package: 'it.dbtecno.pizzaboygba' },
    },
    'systems.json': {
      GBA: {
        name: 'Game Boy Advance',
        emulators: [{ emulator: 'retroarch', core: 'mgba_libretro' }, { emulator: 'retroarch', core: 'gpsp_libretro', name: 'gpSP' }, 'pizzaboy'],
      },
      SD: { name: 'SD card', roms: '/storage/1234/GBA', emulators: ['pizzaboy'] },
    },
    'shortcuts.json': { 'kodi-movies': { name: 'Movies', package: 'org.xbmc.kodi' } },
    'directory.json': { items: [{ system: 'GBA' }] },
    'emulators.template.json': {
      _about: ['x'],
      retroarch: { name: 'RetroArch', package: 'com.retroarch.aarch64' },
      skyemu: { _note: 'Used by GB, GBC, GBA, NDS.', name: 'SkyEmu', package: 'com.sky.SkyEmu', data: { kind: 'file_provider' } },
      noods: { name: 'NooDS', package: 'com.hydra.noods', activity: 'com.hydra.noods.FileBrowser' },
    },
    'systems.template.json': {
      GBA: { name: 'Game Boy Advance', roms: 'GBA', extensions: ['gba', 'zip'], romm: 'gba', emulators: [{ emulator: 'retroarch', core: 'mgba_libretro' }, 'skyemu'] },
      NES: { name: 'Nintendo Entertainment System', roms: 'NES', extensions: ['nes', 'fds'], romm: ['nes', 'famicom'], emulators: [{ emulator: 'retroarch', core: 'nestopia_libretro' }] },
    },
    'shortcuts.template.json': { 'settings-wifi': { name: 'Wi-Fi', package: 'com.android.settings', action: 'android.settings.WIFI_SETTINGS' } },
  },
  hints: {
    apps: [{ package: 'com.retroarch.aarch64', label: 'RetroArch' }, { package: 'com.sky.SkyEmu', label: 'SkyEmu' }],
    cores: { 'com.retroarch.aarch64': ['mgba_libretro', 'snes9x_libretro'] },
    romFolders: ['GBA', 'NES'],
    games: ['GBA/Golden Sun.gba', 'NES/Zelda.nes', '/storage/1234/GBA/Pokemon.gba'],
  },
}

