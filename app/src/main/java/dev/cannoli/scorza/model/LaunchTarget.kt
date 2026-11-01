package dev.cannoli.scorza.model

sealed interface LaunchTarget {
    data object RetroArch : LaunchTarget
}
