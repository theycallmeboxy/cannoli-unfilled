package dev.cannoli.scorza.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cannoli_settings", Context.MODE_PRIVATE)

    private val credPrefs: SharedPreferences =
        context.getSharedPreferences("cannoli_credentials", Context.MODE_PRIVATE)

    private var json = JSONObject()
    private val jsonLock = Any()
    private var settingsFile: File? = null

    private val saveThread = HandlerThread("settings-save").apply { start() }
    private val saveHandler = Handler(saveThread.looper)
    private val saveRunnable = Runnable { saveToDisk() }

    // True while an in-memory mutation has not yet been persisted. reload() must not
    // clobber the json with the on-disk copy while this is set, or un-flushed edits
    // are lost (the bug behind font/text-size/color changes silently reverting).
    @Volatile
    private var pendingSave = false

    private inline fun <T> jsonRead(block: JSONObject.() -> T): T = synchronized(jsonLock) { json.block() }
    private inline fun jsonWrite(block: JSONObject.() -> Unit) { synchronized(jsonLock) { json.block() }; scheduleSave() }

    init {
        loadFromDisk()
        migrateFromPrefs()
        purgeRetroAchievementsCredentials()
    }

    // RetroAchievements lived in the built-in runner, which this build does not have. Its
    // username and token are dropped rather than left behind in storage nothing reads.
    private fun purgeRetroAchievementsCredentials() {
        var changed = false
        synchronized(jsonLock) {
            for (key in listOf(KEY_RA_USERNAME, KEY_RA_TOKEN, KEY_RA_PASSWORD)) {
                if (json.has(key)) {
                    json.remove(key)
                    changed = true
                }
            }
        }
        credPrefs.edit().remove(KEY_RA_TOKEN).remove(KEY_RA_PASSWORD).apply()
        if (changed) saveToDisk()
    }

    private fun loadFromDisk() {
        val file = dev.cannoli.scorza.config.CannoliPaths(sdCardRoot).settingsJson
        settingsFile = file
        if (file.exists()) {
            try { synchronized(jsonLock) { json = JSONObject(file.readText()) } } catch (_: java.io.IOException) {} catch (_: org.json.JSONException) {}
        }
    }

    private fun scheduleSave() {
        pendingSave = true
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 100)
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun saveToDisk() {
        settingsFile?.let { file ->
            if (!setupCompleted) {
                dev.cannoli.scorza.util.ErrorLog.write("settings save skipped: setup not completed (${file.absolutePath})")
                return
            }
            if (!hasStoragePermission()) {
                dev.cannoli.scorza.util.ErrorLog.write("settings save skipped: no storage permission (${file.absolutePath})")
                return
            }
            try {
                file.parentFile?.mkdirs()
                synchronized(jsonLock) { file.writeText(json.toString(2)) }
                pendingSave = false
            } catch (e: IOException) {
                dev.cannoli.scorza.util.ErrorLog.error("settings save failed writing ${file.absolutePath}", e)
            } catch (e: SecurityException) {
                dev.cannoli.scorza.util.ErrorLog.error("settings save failed writing ${file.absolutePath}", e)
            }
        }
    }

    fun flush() {
        saveHandler.removeCallbacks(saveRunnable)
        saveToDisk()
    }

    fun reload() {
        // Un-flushed local edits are newer than disk; persist them rather than letting
        // loadFromDisk overwrite them. Only re-read external changes when nothing is pending.
        if (pendingSave) flush() else loadFromDisk()
    }

    fun shutdown() {
        flush()
        saveThread.quitSafely()
    }

    private fun migrateFromPrefs() {
        synchronized(jsonLock) { if (json.length() > 0) return }
        val keys = prefs.all.keys - KEY_SD_ROOT
        if (keys.isEmpty()) return
        synchronized(jsonLock) {
            for (key in keys) {
                when (val v = prefs.all[key]) {
                    is String -> json.put(key, v)
                    is Boolean -> json.put(key, v)
                    is Int -> json.put(key, v)
                }
            }
        }
        saveToDisk()
        val editor = prefs.edit()
        for (key in keys) editor.remove(key)
        editor.apply()
    }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply() }

    var sdCardRoot: String
        get() = prefs.getString(KEY_SD_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
        set(value) {
            if (value == sdCardRoot) return
            prefs.edit().putString(KEY_SD_ROOT, value).apply()
            settingsFile = dev.cannoli.scorza.config.CannoliPaths(value).settingsJson
            loadFromDisk()
        }

    var romDirectory: String
        get() = jsonRead { optString(KEY_ROM_DIRECTORY, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_ROM_DIRECTORY) else put(KEY_ROM_DIRECTORY, value) }

    var retroArchPackage: String
        get() = jsonRead { optString(KEY_RA_PACKAGE, DEFAULT_RA_PACKAGE) }
        set(value) = jsonWrite { put(KEY_RA_PACKAGE, value) }

    var textSize: TextSize
        get() = TextSize.fromString(jsonRead { if (has(KEY_TEXT_SIZE)) optString(KEY_TEXT_SIZE) else null })
        set(value) = jsonWrite { put(KEY_TEXT_SIZE, value.name) }

    var font: String
        get() = jsonRead { optString(KEY_FONT, "default") }
        set(value) = jsonWrite { put(KEY_FONT, value) }

    var language: String
        get() = jsonRead { optString(KEY_LANGUAGE, "en") }
        set(value) {
            jsonWrite { if (value.isEmpty()) remove(KEY_LANGUAGE) else put(KEY_LANGUAGE, value) }
            dev.cannoli.scorza.i18n.LocaleOverride.persist(context, value)
        }

    var title: String
        get() = jsonRead { optString(KEY_TITLE, "") }
        set(value) = jsonWrite { if (value.isEmpty()) remove(KEY_TITLE) else put(KEY_TITLE, value) }

    var timeFormat: TimeFormat
        get() = TimeFormat.fromString(jsonRead { if (has(KEY_TIME_FORMAT)) optString(KEY_TIME_FORMAT) else null })
        set(value) = jsonWrite { put(KEY_TIME_FORMAT, value.name) }

    var backgroundImagePath: String?
        get() = jsonRead { optString(KEY_BG_IMAGE, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value != null) put(KEY_BG_IMAGE, value) else remove(KEY_BG_IMAGE) }

    var mainMenuQuit: Boolean
        get() = jsonRead { optBoolean(KEY_MAIN_MENU_QUIT, false) }
        set(value) = jsonWrite { put(KEY_MAIN_MENU_QUIT, value) }

    var kitchenCodeBypass: Boolean
        get() = jsonRead { optBoolean(KEY_KITCHEN_CODE_BYPASS, false) }
        set(value) = jsonWrite { put(KEY_KITCHEN_CODE_BYPASS, value) }

    // Opt-in for features that are not ready for release users. Dev builds default it on so a fresh
    // install does not need it flipped by hand.
    var experimentalFeatures: Boolean
        get() = jsonRead { optBoolean(KEY_EXPERIMENTAL_FEATURES, BuildConfig.DEBUG) }
        set(value) = jsonWrite { put(KEY_EXPERIMENTAL_FEATURES, value) }


    var showWifi: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_WIFI, true) }
        set(value) = jsonWrite { put(KEY_SHOW_WIFI, value) }

    var showBluetooth: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_BLUETOOTH, true) }
        set(value) = jsonWrite { put(KEY_SHOW_BLUETOOTH, value) }

    var showVpn: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_VPN, false) }
        set(value) = jsonWrite { put(KEY_SHOW_VPN, value) }

    var showClock: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_CLOCK, true) }
        set(value) = jsonWrite { put(KEY_SHOW_CLOCK, value) }

    var batteryDisplay: BatteryDisplay
        get() = BatteryDisplay.fromString(jsonRead { if (has(KEY_BATTERY_DISPLAY)) optString(KEY_BATTERY_DISPLAY) else null })
        set(value) = jsonWrite { put(KEY_BATTERY_DISPLAY, value.name) }

    val batteryDisplaySet: Boolean
        get() = jsonRead { has(KEY_BATTERY_DISPLAY) }

    var showKitchen: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_KITCHEN, true) }
        set(value) = jsonWrite { put(KEY_SHOW_KITCHEN, value) }

    var showDownloads: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_DOWNLOADS, true) }
        set(value) = jsonWrite { put(KEY_SHOW_DOWNLOADS, value) }

    var concurrentDownloads: Int
        get() = jsonRead { optInt(KEY_CONCURRENT_DOWNLOADS, 2) }.coerceIn(1, 4)
        set(value) = jsonWrite { put(KEY_CONCURRENT_DOWNLOADS, value.coerceIn(1, 4)) }

    var hiddenRommPlatforms: Set<String>
        get() = jsonRead {
            val arr = optJSONArray(KEY_HIDDEN_ROMM_PLATFORMS) ?: return@jsonRead emptySet()
            (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        }
        set(value) = jsonWrite { put(KEY_HIDDEN_ROMM_PLATFORMS, org.json.JSONArray(value.toList())) }


    var showTools: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_TOOLS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_TOOLS, value) }

    var showPorts: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_PORTS, false) }
        set(value) = jsonWrite { put(KEY_SHOW_PORTS, value) }

    var showRecentlyPlayed: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_RECENTLY_PLAYED, true) }
        set(value) = jsonWrite { put(KEY_SHOW_RECENTLY_PLAYED, value) }

    /** Whether Recent lists apps opened from Cannoli alongside games. */
    var showAppsInRecent: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_APPS_IN_RECENT, true) }
        set(value) = jsonWrite { put(KEY_SHOW_APPS_IN_RECENT, value) }

    var showFavorites: Boolean
        get() = jsonRead { optBoolean(KEY_SHOW_FAVORITES, true) }
        set(value) = jsonWrite { put(KEY_SHOW_FAVORITES, value) }

    var scanLibraryAutomatically: Boolean
        get() = jsonRead { optBoolean(KEY_SCAN_LIBRARY_AUTOMATICALLY, true) }
        set(value) = jsonWrite { put(KEY_SCAN_LIBRARY_AUTOMATICALLY, value) }

    var toolsName: String
        get() = jsonRead { optString(KEY_TOOLS_NAME, "Tools").ifEmpty { "Tools" } }
        set(value) = jsonWrite { if (value == "Tools") remove(KEY_TOOLS_NAME) else put(KEY_TOOLS_NAME, value) }

    var portsName: String
        get() = jsonRead { optString(KEY_PORTS_NAME, "Ports").ifEmpty { "Ports" } }
        set(value) = jsonWrite { if (value == "Ports") remove(KEY_PORTS_NAME) else put(KEY_PORTS_NAME, value) }

    var artWidth: Int
        get() = jsonRead { optInt(KEY_ART_WIDTH, 40) }
        set(value) = jsonWrite { put(KEY_ART_WIDTH, value) }

    var artScale: ArtScale
        get() = ArtScale.fromString(jsonRead { if (has(KEY_ART_SCALE)) optString(KEY_ART_SCALE) else null })
        set(value) = jsonWrite { put(KEY_ART_SCALE, value.name) }

    var backgroundTint: Int
        get() = jsonRead { optInt(KEY_BG_TINT, 0) }
        set(value) = jsonWrite { put(KEY_BG_TINT, value.coerceIn(0, 90)) }

    var colorHighlight: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT, value) }

    var colorText: String
        get() = jsonRead { optString(KEY_COLOR_TEXT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_TEXT, value) }

    var colorHighlightText: String
        get() = jsonRead { optString(KEY_COLOR_HIGHLIGHT_TEXT, "#000000") }
        set(value) = jsonWrite { put(KEY_COLOR_HIGHLIGHT_TEXT, value) }

    var colorAccent: String
        get() = jsonRead { optString(KEY_COLOR_ACCENT, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_ACCENT, value) }

    var colorTitle: String
        get() = jsonRead { optString(KEY_COLOR_TITLE, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_TITLE, value) }

    var colorBackground: String
        get() = jsonRead { optString(KEY_COLOR_BACKGROUND, "#000000") }
        set(value) = jsonWrite { put(KEY_COLOR_BACKGROUND, value) }

    var colorStatusBar: String
        get() = jsonRead { optString(KEY_COLOR_STATUS_BAR, "#FFFFFF") }
        set(value) = jsonWrite { put(KEY_COLOR_STATUS_BAR, value) }

    var loggingRomScan: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_ROM_SCAN, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_ROM_SCAN, value) }

    var loggingInput: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_INPUT, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_INPUT, value) }

    var loggingSession: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_SESSION, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_SESSION, value) }

    var loggingKitchen: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_KITCHEN, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_KITCHEN, value) }

    var loggingStorage: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_STORAGE, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_STORAGE, value) }

    var loggingRomm: Boolean
        get() = jsonRead { optBoolean(KEY_LOGGING_ROMM, false) }
        set(value) = jsonWrite { put(KEY_LOGGING_ROMM, value) }

    var portraitMarginPx: Int
        get() = jsonRead { optInt(KEY_PORTRAIT_MARGIN_PX, 0) }
        set(value) = jsonWrite { put(KEY_PORTRAIT_MARGIN_PX, value.coerceAtLeast(0)) }

    var screenGeometryWidth: Int
        get() = jsonRead { optInt(KEY_SCREEN_GEOMETRY_WIDTH, 100) }
        set(value) = jsonWrite { put(KEY_SCREEN_GEOMETRY_WIDTH, value.coerceIn(50, 100)) }

    var screenGeometryHeight: Int
        get() = jsonRead { optInt(KEY_SCREEN_GEOMETRY_HEIGHT, 100) }
        set(value) = jsonWrite { put(KEY_SCREEN_GEOMETRY_HEIGHT, value.coerceIn(50, 100)) }

    var screenGeometryX: Int
        get() = jsonRead { optInt(KEY_SCREEN_GEOMETRY_X, 0) }
        set(value) = jsonWrite { put(KEY_SCREEN_GEOMETRY_X, value.coerceIn(-50, 50)) }

    var screenGeometryY: Int
        get() = jsonRead { optInt(KEY_SCREEN_GEOMETRY_Y, 0) }
        set(value) = jsonWrite { put(KEY_SCREEN_GEOMETRY_Y, value.coerceIn(-50, 50)) }

    var rommDeviceId: String?
        get() = jsonRead { optString(KEY_ROMM_DEVICE_ID, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value == null) remove(KEY_ROMM_DEVICE_ID) else put(KEY_ROMM_DEVICE_ID, value) }

    var rommDeviceName: String?
        get() = jsonRead { optString(KEY_ROMM_DEVICE_NAME, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value == null) remove(KEY_ROMM_DEVICE_NAME) else put(KEY_ROMM_DEVICE_NAME, value) }

    var rommDeviceClientVersion: String?
        get() = jsonRead { optString(KEY_ROMM_DEVICE_CLIENT_VERSION, "").ifEmpty { null } }
        set(value) = jsonWrite { if (value == null) remove(KEY_ROMM_DEVICE_CLIENT_VERSION) else put(KEY_ROMM_DEVICE_CLIENT_VERSION, value) }

    var rommSaveSyncEnabled: Boolean
        get() = jsonRead { optBoolean(KEY_ROMM_SAVE_SYNC_ENABLED, false) }
        set(value) = jsonWrite { put(KEY_ROMM_SAVE_SYNC_ENABLED, value) }

    var rommSaveBackupCount: Int
        get() = jsonRead { optInt(KEY_ROMM_SAVE_BACKUP_COUNT, 5) }
        set(value) = jsonWrite { put(KEY_ROMM_SAVE_BACKUP_COUNT, value.coerceAtLeast(0)) }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Cannoli/"
        const val DEFAULT_RA_PACKAGE = "com.retroarch.aarch64"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_SD_ROOT = "sd_root"
        private const val KEY_ROM_DIRECTORY = "rom_directory"
        private const val KEY_RA_PACKAGE = "ra_package"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_FONT = "font"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TITLE = "title"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_BG_IMAGE = "bg_image"
        private const val KEY_ART_WIDTH = "art_width"
        private const val KEY_ART_SCALE = "art_scale"
        private const val KEY_BG_TINT = "bg_tint"
        private const val KEY_COLOR_HIGHLIGHT = "color_highlight"
        private const val KEY_COLOR_TEXT = "color_text"
        private const val KEY_COLOR_HIGHLIGHT_TEXT = "color_highlight_text"
        private const val KEY_COLOR_ACCENT = "color_accent"
        private const val KEY_COLOR_TITLE = "color_title"
        private const val KEY_COLOR_BACKGROUND = "color_background"
        private const val KEY_COLOR_STATUS_BAR = "color_status_bar"
        private const val KEY_MAIN_MENU_QUIT = "main_menu_quit"
        private const val KEY_KITCHEN_CODE_BYPASS = "kitchen_code_bypass"
        private const val KEY_EXPERIMENTAL_FEATURES = "experimental_features"
        private const val KEY_SHOW_WIFI = "show_wifi"
        private const val KEY_SHOW_BLUETOOTH = "show_bluetooth"
        private const val KEY_SHOW_VPN = "show_vpn"
        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_BATTERY_DISPLAY = "battery_display"
        private const val KEY_SHOW_KITCHEN = "show_kitchen"
        private const val KEY_SHOW_DOWNLOADS = "show_downloads"
        private const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"
        private const val KEY_HIDDEN_ROMM_PLATFORMS = "hidden_romm_platforms"
        private const val KEY_SHOW_TOOLS = "show_tools"
        private const val KEY_SHOW_PORTS = "show_ports"
        private const val KEY_SHOW_RECENTLY_PLAYED = "show_recently_played"
        private const val KEY_SHOW_APPS_IN_RECENT = "show_apps_in_recent"
        private const val KEY_SHOW_FAVORITES = "show_favorites"
        private const val KEY_SCAN_LIBRARY_AUTOMATICALLY = "scan_library_automatically"
        private const val KEY_TOOLS_NAME = "tools_name"
        private const val KEY_PORTS_NAME = "ports_name"
        private const val KEY_RA_USERNAME = "ra_username"
        private const val KEY_RA_TOKEN = "ra_token"
        private const val KEY_RA_PASSWORD = "ra_password"
        private const val KEY_LOGGING_ROM_SCAN = "logging_rom_scan"
        private const val KEY_LOGGING_INPUT = "logging_input"
        private const val KEY_LOGGING_SESSION = "logging_session"
        private const val KEY_LOGGING_KITCHEN = "logging_kitchen"
        private const val KEY_LOGGING_STORAGE = "logging_storage"
        private const val KEY_LOGGING_ROMM = "logging_romm"
        private const val KEY_PORTRAIT_MARGIN_PX = "portrait_margin_px"
        private const val KEY_SCREEN_GEOMETRY_WIDTH = "screen_geometry_width"
        private const val KEY_SCREEN_GEOMETRY_HEIGHT = "screen_geometry_height"
        private const val KEY_SCREEN_GEOMETRY_X = "screen_geometry_x"
        private const val KEY_SCREEN_GEOMETRY_Y = "screen_geometry_y"
        private const val KEY_ROMM_DEVICE_ID = "romm_device_id"
        private const val KEY_ROMM_DEVICE_NAME = "romm_device_name"
        private const val KEY_ROMM_DEVICE_CLIENT_VERSION = "romm_device_client_version"
        private const val KEY_ROMM_SAVE_SYNC_ENABLED = "romm_save_sync_enabled"
        private const val KEY_ROMM_SAVE_BACKUP_COUNT = "romm_save_backup_count"
    }
}

enum class TextSize(val sp: Int) {
    SP10(10), SP12(12), SP14(14),
    SP16(16), SP18(18), SP20(20), SP22(22), SP24(24), SP26(26), SP28(28), SP30(30);
    companion object {
        val DEFAULT = SP24
        fun fromString(value: String?): TextSize = when (value) {
            "COMPACT" -> SP16
            "DEFAULT" -> SP24
            else -> entries.firstOrNull { it.name == value } ?: DEFAULT
        }
    }
}

enum class TimeFormat {
    TWELVE_HOUR, TWENTY_FOUR_HOUR;
    companion object {
        fun fromString(value: String?): TimeFormat =
            entries.firstOrNull { it.name == value } ?: TWELVE_HOUR
    }
}

enum class ArtScale {
    FIT, ORIGINAL, FIT_WIDTH, FIT_HEIGHT;
    companion object {
        val DEFAULT = FIT
        fun fromString(value: String?): ArtScale =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}


enum class BatteryDisplay {
    HIDE, PERCENT, ICON;
    companion object {
        val DEFAULT = PERCENT
        fun fromString(value: String?): BatteryDisplay =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

