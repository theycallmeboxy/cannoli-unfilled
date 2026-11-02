package dev.cannoli.scorza.config.library

import dev.cannoli.scorza.config.AppConfig

/**
 * `{name}` placeholders in an emulator's extra values. The built-in ones are filled at launch by
 * EmulatorIntentBuilder; any other comes from the system entry that lists the emulator, as in
 * `{ "emulator": "retroarch", "core": "mgba_libretro" }` filling `{core}`.
 */
object Placeholders {

    /** Filled in at launch for every game, never by a system entry. */
    val BUILT_IN: Set<String> = setOf("rom_path", "package", "rom_extension", "rom_contents", "rom_id")

    private val PATTERN = Regex("""\{([A-Za-z0-9_]+)\}""")

    /** The placeholder names in [text], in order of first use. */
    fun find(text: String): Set<String> = PATTERN.findAll(text).map { it.groupValues[1] }.toCollection(LinkedHashSet())

    /** Every placeholder in [intent]'s extra values, which are the values substituted at launch. */
    fun usedBy(intent: AppConfig): Set<String> = buildSet {
        for (extra in intent.extras) {
            extra.value?.let { addAll(find(it)) }
            extra.values?.forEach { addAll(find(it)) }
        }
    }

    /** The values a system's [ref] gives, to pass to EmulatorIntentBuilder.resolve as `vars`. */
    fun varsFor(ref: EmulatorRef): Map<String, String> = ref.params.filterKeys { it !in BUILT_IN }

    /** The placeholders [emulator] uses that neither the launcher nor [vars] fills. */
    fun unfilled(emulator: Emulator, vars: Map<String, String>): Set<String> =
        emulator.placeholders - BUILT_IN - vars.keys
}
