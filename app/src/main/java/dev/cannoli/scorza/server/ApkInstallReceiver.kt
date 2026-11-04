package dev.cannoli.scorza.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.cannoli.scorza.util.KitchenLog
import javax.inject.Inject

@AndroidEntryPoint
class ApkInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var installer: ApkInstaller

    override fun onReceive(context: Context, intent: Intent) {
        val installId = intent.getStringExtra(EXTRA_INSTALL_ID) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    installer.fail(installId, "missing confirmation intent")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    KitchenLog.logError("apk confirm launch failed", e)
                    installer.fail(installId, "could not show install confirmation")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> installer.complete(installId)
            else -> installer.fail(installId, failureMessage(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
        }
    }

    companion object {
        /** Plain words for the failures a user can act on, followed by Android's own message. */
        internal fun failureMessage(status: Int, detail: String?): String {
            val reason = when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled on the device."
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "The device blocked the install."
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    "It conflicts with an installed app: an app with the same name but a different signature is already installed."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The app isn't compatible with this device."
                PackageInstaller.STATUS_FAILURE_INVALID -> "The file isn't a valid APK."
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage on the device."
                else -> "Install failed ($status)."
            }
            return if (detail.isNullOrBlank()) reason else "$reason $detail"
        }

        const val ACTION_RESULT = "dev.cannoli.scorza.server.APK_INSTALL_RESULT"
        const val EXTRA_INSTALL_ID = "install_id"
    }
}
