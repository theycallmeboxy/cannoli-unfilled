package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.library.LibraryParser
import dev.cannoli.scorza.config.library.LibraryValidator
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rows of "Launch With" and "Default Emulator", with their marks. */
class EmulatorChoicesTest {

    private val library = LibraryValidator.validate(
        LibraryParser.parseEmulators(
            """
            {
              "retroarch": { "name": "RetroArch", "package": "ra",
                "extras": [ { "key": "LIBRETRO", "kind": "string", "value": "{core}" } ] },
              "pizzaboy": { "name": "Pizza Boy", "package": "pb" },
              "other": { "package": "ot" }
            }
            """.trimIndent()
        ).value,
        LibraryParser.parseSystems(
            """
            { "GBA": { "emulators": [
                { "emulator": "retroarch", "core": "mgba_libretro" },
                { "emulator": "retroarch", "core": "gpsp_libretro", "name": "gpSP" },
                { "emulator": "retroarch", "core": "vba_next_libretro", "name": "VBA" },
                "pizzaboy",
                "other"
            ] } }
            """.trimIndent()
        ).value,
        emptyMap(), emptyList(), emptyMap(),
    ).library

    private val gba = library.systems.getValue("GBA")

    private fun build(
        current: String?,
        installed: Set<String> = setOf("ra", "pb", "ot"),
        cores: Map<String, CoreStatus> = emptyMap(),
    ) = EmulatorChoices.build(
        library, gba, current,
        isInstalled = { it in installed },
        coreStatus = { _, core -> cores[core] ?: CoreStatus.UNKNOWN },
    )

    @Test fun `rows follow the system's order with their labels and the default first`() {
        val rows = build(current = "retroarch")
        assertEquals(
            listOf("RetroArch · mgba_libretro", "gpSP", "VBA", "Pizza Boy", "other"),
            rows.map { it.label },
        )
        assertEquals(listOf("retroarch", "gpSP", "VBA", "pizzaboy", "other"), rows.map { it.name })
        assertEquals(listOf(true, false, false, false, false), rows.map { it.isDefault })
        assertEquals(listOf(true, false, false, false, false), rows.map { it.isCurrent })
    }

    @Test fun `the game's override is the current row`() {
        assertEquals(listOf(false, false, false, true, false), build(current = "pizzaboy").map { it.isCurrent })
    }

    @Test fun `a missing app or a core the emulator reports missing is marked, unknown is not`() {
        val rows = build(
            current = "retroarch",
            installed = setOf("ra", "ot"),
            cores = mapOf(
                "mgba_libretro" to CoreStatus.PRESENT,
                "gpsp_libretro" to CoreStatus.MISSING,
                "vba_next_libretro" to CoreStatus.UNKNOWN,
            ),
        )
        assertEquals(
            listOf(ChoiceMark.NONE, ChoiceMark.CORE_MISSING, ChoiceMark.NONE, ChoiceMark.APP_MISSING, ChoiceMark.NONE),
            rows.map { it.mark },
        )
    }

    @Test fun `an app that isn't installed is marked so even when its core is missing too`() {
        val rows = build(current = null, installed = emptySet(), cores = mapOf("mgba_libretro" to CoreStatus.MISSING))
        assertEquals(ChoiceMark.APP_MISSING, rows.first().mark)
    }
}
