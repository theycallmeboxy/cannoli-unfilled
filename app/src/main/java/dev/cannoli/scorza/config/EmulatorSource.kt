package dev.cannoli.scorza.config

import dev.cannoli.scorza.R

enum class EmulatorSource(val displayName: String) {
    RetroArch("RetroArch"),
    Standalone("Standalone");

    val emptyMessageRes: Int
        get() = when (this) {
            RetroArch -> R.string.value_no_cores_found
            Standalone -> R.string.value_none_installed
        }

    companion object {
        /**
         * Parses a stored source name. "Internal" named the built-in libretro runner, which this
         * build no longer has, so those choices fall to RetroArch with the same core.
         */
        fun fromStoredName(name: String?): EmulatorSource? =
            if (name == "Internal") RetroArch else entries.firstOrNull { it.name == name }

        /**
         * Migration only. v1 cores.json stored the picker's display caption as the runner, so a
         * source has to be recovered from it. Nothing at runtime may parse a caption back into a
         * source: options and choices carry [EmulatorSource] directly.
         */
        fun fromRunnerLabel(label: String?): EmulatorSource? = when (label) {
            "Standalone", "App" -> Standalone
            null, "" -> null
            else -> RetroArch
        }
    }
}
