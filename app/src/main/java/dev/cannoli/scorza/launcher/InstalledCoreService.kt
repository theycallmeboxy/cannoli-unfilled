package dev.cannoli.scorza.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.library.LibraryConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

const val ACTION_QUERY_INSTALLED_CORES = "com.retroarch.QUERY_INSTALLED_CORES"
const val ACTION_INSTALLED_CORES_RESULT = "com.retroarch.INSTALLED_CORES_RESULT"

/** What a package's last report says about one core. */
enum class CoreStatus { PRESENT, MISSING, UNKNOWN }

// UNSCANNED and NOT_INSTALLED exist because absence of information is not information. Folding
// them into REPORTS made the picker label every core Not Installed both before the boot scan
// landed and when no RetroArch was installed at all.
enum class CoreReporting { REPORTS, UNSUPPORTED, SILENT, NOT_INSTALLED, UNSCANNED }

// Resolving the receiver answers the real question. Version numbers cannot: every nightly
// reports the same versionName, and the Play Store flavor computes versionCode by an
// unrelated formula that would permanently fail a date comparison.
fun PackageManager.hasCoreQueryReceiver(packageName: String): Boolean =
    queryBroadcastReceivers(Intent(ACTION_QUERY_INSTALLED_CORES).setPackage(packageName), 0)
        .any { it.activityInfo?.packageName == packageName }

/**
 * Asks emulators which libretro cores they have, through the broadcast upstream RetroArch's
 * InstalledCoresReceiver answers. Nothing here is specific to RetroArch: the packages asked are
 * those of the emulators in emulators.json that declare a receiver for the query.
 */
@Singleton
class InstalledCoreService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryConfig: LibraryConfig,
) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var installedCores: Map<String, Set<String>> = emptyMap()
        private set

    @Volatile
    var unresponsivePackages: Set<String> = emptySet()
        private set

    @Volatile
    var unsupportedPackages: Set<String> = emptySet()
        private set

    @Volatile
    var knownPackages: Set<String> = emptySet()
        private set

    @Volatile
    var cacheReady: Boolean = false
        private set

    suspend fun queryAllPackages() {
        val answered = mutableMapOf<String, Set<String>>()
        val silent = mutableSetOf<String>()
        val unsupported = mutableSetOf<String>()
        val discovered = emulatorPackages()
        for (pkg in discovered) {
            // No receiver means no reply is coming, so paying the timeout would only stall
            // the scan by three seconds per package to learn nothing.
            if (!context.packageManager.hasCoreQueryReceiver(pkg)) {
                unsupported.add(pkg)
                continue
            }
            // An empty reply is not the same as no reply: a fresh RetroArch has no cores and
            // genuinely answers zero, which must read as "missing", not "unknown".
            val cores = queryPackage(pkg)
            if (cores == null) silent.add(pkg) else answered[pkg] = cores
        }
        publishQueryResult(answered, silent, unsupported, discovered.toSet())
    }

    @Synchronized
    private fun publishQueryResult(
        answered: Map<String, Set<String>>,
        silent: Set<String>,
        unsupported: Set<String>,
        known: Set<String>,
    ) {
        val merged = HashMap<String, Set<String>>()
        // A package that did not answer keeps whatever was cached, since there is no fresh
        // information about it.
        for ((pkg, cores) in installedCores) if (pkg !in answered) merged[pkg] = cores
        // A package that did answer is replaced wholesale, so a core deleted inside the emulator
        // stops being reported.
        merged.putAll(answered)
        installedCores = merged
        unresponsivePackages = silent
        unsupportedPackages = unsupported
        knownPackages = known
        cacheReady = true
    }

    // Every installed package an emulators.json entry launches.
    private fun emulatorPackages(): List<String> =
        libraryConfig.library.emulators.values
            .map { it.intent.packageName }
            .distinct()
            .filter { context.packageManager.isPackageInstalled(it) }

    /** Null means the package never answered. An empty set means it answered with no cores. */
    private suspend fun queryPackage(pkg: String, timeoutMs: Long = 3000L): Set<String>? =
        suspendCancellableCoroutine { cont ->
            val token = Any()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val cores = intent.getStringArrayExtra("CORES")
                        ?.map { soToCoreId(it) }?.toSet() ?: emptySet()
                    handler.removeCallbacksAndMessages(token)
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(cores)
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_INSTALLED_CORES_RESULT),
                Context.RECEIVER_EXPORTED
            )

            context.sendBroadcast(Intent(ACTION_QUERY_INSTALLED_CORES).apply {
                setPackage(pkg)
            })

            handler.postAtTime({
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) cont.resume(null)
            }, token, android.os.SystemClock.uptimeMillis() + timeoutMs)

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                handler.removeCallbacksAndMessages(token)
            }
        }

    // Ordered so the two "we have nothing to go on" cases are settled before any classification
    // drawn from a scan, since those sets are only meaningful once a scan has actually run.
    fun reportingFor(pkg: String): CoreReporting = when {
        !cacheReady -> CoreReporting.UNSCANNED
        pkg !in knownPackages -> CoreReporting.NOT_INSTALLED
        pkg in unsupportedPackages -> CoreReporting.UNSUPPORTED
        pkg in unresponsivePackages -> CoreReporting.SILENT
        else -> CoreReporting.REPORTS
    }

    /**
     * Whether [packageName] has [core] (as systems.json names it, e.g. mgba_libretro). Only a
     * package that answered the query can say a core is missing; anything else is unknown.
     */
    fun coreStatus(packageName: String, core: String): CoreStatus = when {
        reportingFor(packageName) != CoreReporting.REPORTS -> CoreStatus.UNKNOWN
        installedCores[packageName]?.contains(core) == true -> CoreStatus.PRESENT
        else -> CoreStatus.MISSING
    }

    companion object {
        fun soToCoreId(filename: String): String =
            filename.removeSuffix("_android.so").removeSuffix(".so")
    }
}
