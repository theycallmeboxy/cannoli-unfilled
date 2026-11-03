package dev.cannoli.scorza.romm

/**
 * Turns RomM's platforms into the ones Cannoli shows: each matched to a system by its slug
 * (systems.json `romm`), and dropped when no system claims it.
 */
class PlatformMap(private val tagForSlug: (slug: String) -> String?) {
    fun toDomain(dtos: List<PlatformDto>): List<RommPlatform> =
        dtos.mapNotNull { dto ->
            val tag = tagForSlug(dto.fsSlug.ifEmpty { dto.slug }) ?: return@mapNotNull null
            RommPlatform(
                id = dto.id,
                slug = dto.slug,
                cannoliTag = tag,
                displayName = dto.displayName.ifEmpty { dto.name },
                romCount = dto.romCount,
                firmwareCount = dto.firmware.size,
            )
        }
}
