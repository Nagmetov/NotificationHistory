package com.notificationhistory.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.notificationhistory.service.NotificationCaptureService

/** Переходы в системные настройки, которые нужны приложению для стабильной работы. */
object SystemSettings {

    private val manufacturer = Build.MANUFACTURER.lowercase()

    /** Оболочки, которые без ручной настройки выгружают фоновые приложения. */
    val isAggressiveOem: Boolean =
        listOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo", "meizu")
            .any { it in manufacturer }

    val isXiaomi: Boolean = listOf("xiaomi", "redmi", "poco").any { it in manufacturer }

    fun openListenerSettings(context: Context) {
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                NotificationCaptureService.component(context).flattenToString(),
            )
        } else null
        if (detail == null || !tryStart(context, detail)) {
            tryStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    fun openAppDetails(context: Context) {
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Системный диалог «Разрешить работу в фоне без ограничений?». */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val ok = tryStart(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))
        if (!ok) tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * Экран «Контроль активности» Xiaomi (там нужно выбрать «Нет ограничений»);
     * на других телефонах — настройки приложения.
     */
    fun openBatterySaverSettings(context: Context) {
        if (isXiaomi) {
            val label = context.applicationInfo.loadLabel(context.packageManager).toString()
            // HyperOS — «Сведения о батарее» приложения; старые MIUI — отдельный экран powerkeeper.
            val screens = listOf(
                "com.miui.securitycenter" to "com.miui.powercenter.legacypowerrank.PowerDetailActivity",
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )
            for ((pkg, cls) in screens) {
                val intent = Intent().setComponent(ComponentName(pkg, cls))
                    .putExtra("package_name", context.packageName)
                    .putExtra("package_label", label)
                if (tryStart(context, intent)) return
            }
        }
        openAppDetails(context)
    }

    /** Экран автозапуска у производителей, у которых он есть. */
    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )
        for ((pkg, cls) in candidates) {
            if (tryStart(context, Intent().setComponent(ComponentName(pkg, cls)))) return
        }
        openAppDetails(context)
    }

    fun openAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ok = tryStart(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)))
        if (!ok) tryStart(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // Некоторые экраны оболочек закрыты для сторонних приложений.
        false
    }
}
