package dev.cannoli.scorza.romm

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformMapTest {

    private val tags = mapOf("snes" to "SNES", "sfc" to "SNES", "gba" to "GBA")
    private val map = PlatformMap { slug -> tags[slug.lowercase()] }

    private fun dto(id: Int, slug: String, name: String, count: Int, fsSlug: String = "") =
        PlatformDto(id = id, slug = slug, fsSlug = fsSlug, name = name, displayName = name, romCount = count)

    @Test fun `maps platforms a system claims and drops the rest`() {
        val result = map.toDomain(listOf(
            dto(1, "snes", "Super Nintendo", 5),
            dto(2, "gba", "Game Boy Advance", 9),
            dto(3, "unmapped", "No System", 1),
        ))
        assertEquals(listOf("SNES", "GBA"), result.map { it.cannoliTag })
        assertEquals(5, result.first().romCount)
        assertEquals("Super Nintendo", result.first().displayName)
    }

    @Test fun `several slugs can map to one system`() {
        val result = map.toDomain(listOf(dto(1, "snes", "SNES", 1), dto(2, "SFC", "Super Famicom", 1)))
        assertEquals(listOf("SNES", "SNES"), result.map { it.cannoliTag })
    }

    @Test fun `the folder slug wins over the platform slug`() {
        val result = map.toDomain(listOf(dto(1, slug = "unmapped", name = "x", count = 1, fsSlug = "gba")))
        assertEquals(listOf("GBA"), result.map { it.cannoliTag })
        assertEquals("unmapped", result.single().slug)
    }

    @Test fun `empty when no system claims anything`() {
        val none = PlatformMap { null }
        assertEquals(emptyList<RommPlatform>(), none.toDomain(listOf(dto(1, "snes", "SNES", 1))))
    }

    @Test fun `maps firmware count from firmware list`() {
        val result = map.toDomain(listOf(
            dto(1, "snes", "Super Nintendo", 1).copy(
                firmware = listOf(FirmwareDto(id = 1, fileName = "a.bin"), FirmwareDto(id = 2, fileName = "b.bin"))),
            dto(2, "gba", "Game Boy Advance", 1),
        ))
        assertEquals(2, result.first { it.cannoliTag == "SNES" }.firmwareCount)
        assertEquals(0, result.first { it.cannoliTag == "GBA" }.firmwareCount)
    }
}
