package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.Placeholders
import java.io.File

object RomKeys {
    fun relativeKey(rom: File, romRoot: File): String =
        rom.absolutePath.removePrefix(romRoot.absolutePath).removePrefix(File.separator)

    /**
     * The emulator name sent with a save: the choice's own name when it has one, else its core,
     * else the emulator's name. Null when the game's system has no emulator to launch it with.
     */
    fun emulatorNameFor(platformConfig: PlatformConfig, tag: String, gameKey: String): String? {
        val plan = platformConfig.launchFor(tag, gameKey) ?: return null
        return plan.ref.label ?: plan.ref.params[Placeholders.CORE] ?: plan.emulator.label
    }
}
