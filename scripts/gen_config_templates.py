#!/usr/bin/env python3
"""Generate the Config/*.template.json assets from app/src/main/assets/platforms.json.

platforms.json is the built-in platform list: a top-level "apps" map of shared intents (one of
them "retroarch"), then one entry per platform tag with "name", "core", "arcade" and "app", a list
whose items are a package name, the name of an "apps" intent, or a full intent object.

This writes, under app/src/main/assets/templates/:
  emulators.template.json  "retroarch" plus one entry per distinct standalone intent
  systems.template.json    every platform, with RetroArch first when it has a core
  shortcuts.template.json  a few worked examples of custom intents

Cannoli copies these into Config/ on every start and never reads them as config. Run it again
whenever platforms.json changes:  python3 scripts/gen_config_templates.py
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
SOURCE = ASSETS / "platforms.json"
OUT = ASSETS / "templates"

RETROARCH = "retroarch"

# Package segments that only qualify the one before them ("pro", "nightly", "emulator"...). A
# name never ends on one of these alone: the segment before it is joined on.
QUALIFIERS = {
    "alpha", "amazon", "android", "basic", "beta", "debug", "deluxe", "emu", "emulate", "emulator",
    "free", "lite", "nightly", "plus", "premium", "pro", "release",
}

# Leading segments that say nothing about the app.
TLDS = {"com", "org", "io", "net", "me", "it", "ru", "xyz", "info", "dev", "app"}

# Display names for packages whose own name says little. Anything not listed shows its package.
DISPLAY_NAMES = {
    "com.sky.SkyEmu": "SkyEmu",
    "com.explusalpha.GbcEmu": "GBC.emu",
    "com.explusalpha.GbaEmu": "GBA.emu",
    "com.explusalpha.NesEmu": "NES.emu",
    "com.explusalpha.Snes9xPlus": "Snes9x EX+",
    "com.explusalpha.MdEmu": "MD.emu",
    "com.explusalpha.SaturnEmu": "Saturn.emu",
    "com.explusalpha.LynxEmu": "Lynx.emu",
    "com.explusalpha.A2600Emu": "2600.emu",
    "com.explusalpha.NgpEmu": "NGP.emu",
    "com.explusalpha.SwanEmu": "Swan.emu",
    "com.explusalpha.NeoEmu": "NEO.emu",
    "com.explusalpha.MsxEmu": "MSX.emu",
    "com.PceEmu": "PCE.emu",
    "com.fastemulator.gbc": "My OldBoy!",
    "com.fastemulator.gba": "My Boy!",
    "com.pixelrespawn.linkboy": "Linkboy",
    "it.dbtecno.pizzaboy": "Pizza Boy GBC",
    "it.dbtecno.pizzaboypro": "Pizza Boy GBC Pro",
    "it.dbtecno.pizzaboygba": "Pizza Boy GBA",
    "it.dbtecno.pizzaboygbapro": "Pizza Boy GBA Pro",
    "it.dbtecno.pizzaboyscbasic": "Pizza Boy SC Basic",
    "it.dbtecno.pizzaboyscpro": "Pizza Boy SC Pro",
    "com.hydra.noods": "NooDS",
    "com.fms.ines.free": "iNES",
    "com.fms.mg": "MasterGear",
    "com.fms.colem": "ColEm",
    "com.fms.colem.deluxe": "ColEm Deluxe",
    "com.androidemu.nes": "Nesoid",
    "org.mupen64plusae.v3.fzurita": "Mupen64Plus FZ",
    "org.mupen64plusae.v3.fzurita.pro": "Mupen64Plus FZ Pro",
    "org.mupen64plusae.v3.fzurita.amazon": "Mupen64Plus FZ (Amazon)",
    "org.mupen64plusae.v3.alpha": "Mupen64Plus AE",
    "com.m64.fx.plus.emulate": "M64Plus FX",
    "me.magnum.melonds": "melonDS",
    "me.magnum.melondualds": "melonDS Dual",
    "me.magnum.melonds.nightly": "melonDS Nightly",
    "com.dsemu.drastic": "DraStic",
    "org.devmiyax.yabasanshioro2": "Yaba Sanshiro 2",
    "org.devmiyax.yabasanshioro2.pro": "Yaba Sanshiro 2 Pro",
    "com.github.stenzek.duckstation": "DuckStation",
    "com.emulator.fpse": "FPse",
    "com.emulator.fpse64": "FPse64",
    "org.ppsspp.ppsspp": "PPSSPP",
    "org.ppsspp.ppssppgold": "PPSSPP Gold",
    "com.flycast.emulator": "Flycast",
    "io.recompiled.redream": "Redream",
    "com.seleuco.mame4droid": "MAME4droid",
    "com.fishstix.dosbox": "DosBox Turbo",
    "bruenor.magicbox": "Magic Dosbox",
    "org.scummvm.scummvm": "ScummVM",
    "app.gamenative": "GameNative",
    "gamehub.lite": "GameHub Lite",
    "xyz.aethersx2.android": "AetherSX2",
    "com.armsx2": "ARMSX2",
    "org.dolphinemu.dolphinemu": "Dolphin",
    "org.dolphinemu.mmjr": "Dolphin MMJR",
    "org.azahar_emu.azahar": "Azahar",
    "info.cemu.cemu": "Cemu",
    "org.vita3k.emulator": "Vita3K",
    "org.vita3k.emulator.ikhoeyZX": "Vita3K (ikhoeyZX)",
    "aenu.aps3e": "aPS3e",
    "aenu.aps3e.premium": "aPS3e Premium",
    "dev.eden.eden_emulator": "Eden",
    "dev.eden.eden_emulator.nightly": "Eden Nightly",
}

EMULATORS_ABOUT = [
    "Every emulator Cannoli knows how to launch. This file is rewritten on every update and never read as config.",
    "To use one, copy its entry into emulators.json (same key), then list that key in a system in systems.json.",
    "The key is the emulator's name. The value is a launch intent (see CONFIG.md) plus an optional display \"name\".",
    "Placeholders in extra values: {rom_path}, {package}, {rom_extension}, {rom_contents} and {rom_id} are always filled in.",
    "Any other placeholder, such as {core}, is filled from the system entry that lists the emulator.",
    "Keys starting with _ are ignored everywhere, so a copied entry can keep its _note.",
]

SYSTEMS_ABOUT = [
    "Every system Cannoli knows. This file is rewritten on every update and never read as config.",
    "To use one, copy its entry into systems.json, and the emulators it lists from emulators.template.json into emulators.json.",
    "Then place the system in directory.json, e.g. { \"system\": \"GBA\" }, to show it on the menu.",
    "\"roms\" is its folder under Roms/ (defaults to the key). \"extensions\" limits the file types; leave it out to accept every file.",
    "\"emulators\" lists what can run it, the first being the default: an emulator name, or an object with \"emulator\" plus values for its placeholders, such as \"core\".",
    "An object can also take a \"name\", which overrides.json and the picker use when the same emulator is listed twice.",
]

SHORTCUTS_ABOUT = [
    "Examples of shortcuts: launch intents that act like an app rather than a system. Never read as config.",
    "To use one, copy it into shortcuts.json (same key) and place it in directory.json, e.g. { \"shortcut\": \"settings-wifi\" }.",
    "Each takes a \"name\" for the menu plus a launch intent (see CONFIG.md). Without an \"action\" it opens like a home screen icon.",
    "A shortcut takes no game, so data kinds and extras that pass the game file (file_provider, path, uri_string...) don't apply.",
    "To open a URI, use data kind custom_scheme: the URI is scheme://authority.",
]

SHORTCUTS = {
    "settings-wifi": {
        "_note": "An action on its own: Android's Wi-Fi settings.",
        "name": "Wi-Fi",
        "package": "com.android.settings",
        "action": "android.settings.WIFI_SETTINGS",
    },
    "cannoli-notifications": {
        "_note": "A named activity with a fixed extra: this app's notification settings.",
        "name": "Cannoli Notifications",
        "package": "com.android.settings",
        "activity": "com.android.settings.Settings$AppNotificationSettingsActivity",
        "action": "android.settings.APP_NOTIFICATION_SETTINGS",
        "extras": [
            {"key": "android.provider.extra.APP_PACKAGE", "kind": "string", "value": "io.github.theycallmeboxy.cannoli"},
        ],
    },
    "web-manual": {
        "_note": "VIEW of a web page in a browser. The URI is https://<authority>.",
        "name": "RetroArch Docs",
        "package": "com.android.chrome",
        "action": "android.intent.action.VIEW",
        "data": {"kind": "custom_scheme", "scheme": "https", "authority": "docs.libretro.com"},
    },
    "kodi-movies": {
        "_note": "VIEW with an app's own scheme: opens a Kodi add-on.",
        "name": "Movies",
        "package": "org.xbmc.kodi",
        "action": "android.intent.action.VIEW",
        "data": {"kind": "custom_scheme", "scheme": "plugin", "authority": "plugin.video.example"},
    },
}


def name_from(segs, take):
    """Joins the last [take] segments, widened while the leftmost one only qualifies."""
    take = min(take, len(segs))
    while take < len(segs) and (segs[-take] in QUALIFIERS or len(segs[-take]) <= 3):
        take += 1
    return "-".join(segs[-take:])


def unique_names(packages):
    """Maps each package to a key, widening colliding keys with more of their package."""
    segs = {}
    for p in packages:
        s = [x.lower().replace("_", "-") for x in p.split(".")]
        segs[p] = s[1:] if len(s) > 1 and s[0] in TLDS else s
    take = {p: 1 for p in packages}
    while True:
        names = {p: name_from(segs[p], take[p]) for p in packages}
        clashes = {}
        for p, n in names.items():
            clashes.setdefault(n, []).append(p)
        widened = False
        for n, ps in clashes.items():
            if n == RETROARCH or len(ps) > 1:
                for p in ps:
                    if take[p] < len(segs[p]):
                        take[p] += 1
                        widened = True
        if not widened:
            break
    # Anything still equal (only possible for packages differing in case) gets a number.
    seen, out = {}, {}
    for p in packages:
        n = names[p]
        if n in seen or n == RETROARCH:
            k = 2
            while f"{n}-{k}" in seen:
                k += 1
            n = f"{n}-{k}"
        seen[n] = p
        out[p] = n
    return out


def canonical(intent):
    return json.dumps(intent, sort_keys=True)


def main():
    platforms = json.loads(SOURCE.read_text())
    templates = platforms.get("apps", {})
    if RETROARCH not in templates:
        raise SystemExit("platforms.json has no apps.retroarch template")

    # Every standalone intent, in first-seen order, with the systems that list it.
    variants = {}  # package -> list of (canonical intent, intent, [tags])
    per_platform = {}  # tag -> list of (package, variant index)
    for tag, entry in platforms.items():
        if tag == "apps":
            continue
        apps = entry.get("app", [])
        if isinstance(apps, str):
            apps = [apps]
        refs = []
        for item in apps:
            if isinstance(item, str):
                intent = dict(templates[item]) if item in templates else {"package": item}
            else:
                intent = dict(item)
            pkg = intent["package"]
            key = canonical(intent)
            vs = variants.setdefault(pkg, [])
            idx = next((i for i, v in enumerate(vs) if v[0] == key), None)
            if idx is None:
                vs.append((key, intent, []))
                idx = len(vs) - 1
            if tag not in vs[idx][2]:
                vs[idx][2].append(tag)
            refs.append((pkg, idx))
        per_platform[tag] = refs

    names = unique_names(list(variants))

    def variant_name(pkg, idx):
        base = names[pkg]
        return base if idx == 0 else (f"{base}-alt" if idx == 1 else f"{base}-alt{idx}")

    emulators = {"_about": EMULATORS_ABOUT}
    ra = {"_note": "RetroArch 64-bit. Each system passes its core as {core}, e.g. \"core\": \"mgba_libretro\".",
          "name": "RetroArch"}
    ra.update(templates[RETROARCH])
    emulators[RETROARCH] = ra
    for pkg, vs in variants.items():
        for idx, (_, intent, tags) in enumerate(vs):
            label = DISPLAY_NAMES.get(pkg, pkg)
            if idx > 0:
                label = f"{label} (alternate intent)"
            entry = {"_note": "Used by " + ", ".join(tags) + ".", "name": label}
            entry.update(intent)
            emulators[variant_name(pkg, idx)] = entry

    systems = {"_about": SYSTEMS_ABOUT}
    for tag, entry in platforms.items():
        if tag == "apps":
            continue
        system = {"name": entry.get("name", tag), "roms": tag}
        if "extensions" in entry:
            system["extensions"] = entry["extensions"]
        if entry.get("arcade"):
            system["arcade"] = True
        ems = []
        if entry.get("core"):
            ems.append({"emulator": RETROARCH, "core": entry["core"]})
        ems.extend(variant_name(pkg, idx) for pkg, idx in per_platform[tag])
        system["emulators"] = ems
        systems[tag] = system

    shortcuts = {"_about": SHORTCUTS_ABOUT}
    shortcuts.update(SHORTCUTS)

    OUT.mkdir(parents=True, exist_ok=True)
    for name, doc in (("emulators", emulators), ("systems", systems), ("shortcuts", shortcuts)):
        path = OUT / f"{name}.template.json"
        path.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
        print(f"{path.relative_to(ROOT)}: {len(doc) - 1} entries")


if __name__ == "__main__":
    main()
