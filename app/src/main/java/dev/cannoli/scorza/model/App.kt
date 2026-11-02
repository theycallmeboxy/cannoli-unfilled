package dev.cannoli.scorza.model

import java.io.File

data class App(
    val id: Long,
    val type: AppType,
    val displayName: String,
    val packageName: String,
    val lastPlayedAt: Long? = null,
    val artFile: File? = null,
)

enum class AppType { TOOL, PORT }

val AppType.artTag: String get() = if (this == AppType.TOOL) "TOOLS" else "PORTS"

/**
 * A shortcut from shortcuts.json has an apps row like an app, for favorites and recently played,
 * filed under this prefix plus the shortcut's name in place of a package name.
 */
const val SHORTCUT_PACKAGE_PREFIX = "shortcut:"

/** The shortcuts.json name this row stands for, or null for a real app. */
val App.shortcutName: String? get() = packageName.takeIf { it.startsWith(SHORTCUT_PACKAGE_PREFIX) }?.removePrefix(SHORTCUT_PACKAGE_PREFIX)
