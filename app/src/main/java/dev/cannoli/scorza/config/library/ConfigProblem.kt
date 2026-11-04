package dev.cannoli.scorza.config.library

/**
 * How bad a [ConfigProblem] is. An [ERROR] means something written in the files was not loaded at
 * all: a file that is not valid JSON, or a whole entry skipped because it is malformed. A
 * [WARNING] drops one option or reference, or ignores one key, and everything else still works.
 */
enum class Severity { ERROR, WARNING }

/** One problem found reading the config files. [message] starts with the file's name. */
data class ConfigProblem(val severity: Severity, val message: String) {
    /** As Logs/error.log has it: `WARNING systems.json: ...`. */
    val logLine: String get() = "${severity.name} $message"

    override fun toString() = logLine

    companion object {
        fun error(message: String) = ConfigProblem(Severity.ERROR, message)
        fun warning(message: String) = ConfigProblem(Severity.WARNING, message)
    }
}

/** How many of these are errors, then warnings. */
fun List<ConfigProblem>.severityCounts(): Pair<Int, Int> {
    val errors = count { it.severity == Severity.ERROR }
    return errors to size - errors
}
