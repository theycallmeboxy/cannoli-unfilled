package dev.cannoli.scorza.server

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.util.KitchenLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkInstalls {

    private val statuses = ConcurrentHashMap<String, InstallStatus>()

    override val stagingDir: File
        get() = File(context.cacheDir, "kitchen-apk").apply { mkdirs() }

    override fun begin(apk: File): String {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val installId = sessionId.toString()
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                statuses[installId] = InstallStatus(InstallStatus.PENDING_USER)
                session.commit(resultIntent(sessionId, installId).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            statuses.remove(installId)
            throw e
        } finally {
            // The session holds its own copy once written, so the upload isn't needed either way.
            apk.delete()
        }
        KitchenLog.log("apk install $installId committed for ${apk.name}")
        return installId
    }

    override fun status(installId: String): InstallStatus? = statuses[installId]

    /** Whether the user has allowed this app to install unknown apps (Android 8+). */
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens this app's "Install unknown apps" switch on the device's screen. */
    override fun openInstallSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        KitchenLog.logError("could not open install settings", e)
        false
    }

    internal fun complete(installId: String) {
        statuses[installId] = InstallStatus(InstallStatus.SUCCESS)
        KitchenLog.log("apk install $installId succeeded")
    }

    internal fun fail(installId: String, message: String) {
        statuses[installId] = InstallStatus(InstallStatus.FAILURE, message)
        KitchenLog.log("apk install $installId failed: $message")
    }

    private fun resultIntent(sessionId: Int, installId: String): PendingIntent {
        val intent = Intent(context, ApkInstallReceiver::class.java)
            .setAction(ApkInstallReceiver.ACTION_RESULT)
            .putExtra(ApkInstallReceiver.EXTRA_INSTALL_ID, installId)
        // FLAG_MUTABLE required on API 31+ so the system can fill in EXTRA_STATUS and the confirmation intent
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}
