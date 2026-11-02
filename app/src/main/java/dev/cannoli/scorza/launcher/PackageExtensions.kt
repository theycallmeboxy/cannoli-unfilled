package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun Context.startActivityNoAnim(intent: Intent, fallbackMsg: String): LaunchResult = try {
    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
    LaunchResult.Success
} catch (e: Exception) {
    LaunchResult.Error(e.message ?: fallbackMsg)
}

fun Context.romFileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
