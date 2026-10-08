package com.notificationhistory.data

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Сколько дней хранить историю; 0 — бессрочно. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION, 30)
        set(value) = prefs.edit { putInt(KEY_RETENTION, value) }

    /** Сохранять ли постоянные уведомления (музыка, загрузки, фоновые сервисы). */
    var saveOngoing: Boolean
        get() = prefs.getBoolean(KEY_ONGOING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONGOING, value) }

    /** Копировать фото, стикеры и прочие файлы из папок WhatsApp/Telegram. */
    var mediaBackup: Boolean
        get() = prefs.getBoolean(KEY_MEDIA, true)
        set(value) = prefs.edit { putBoolean(KEY_MEDIA, value) }

    /** С какого момента копируем медиа — чтобы при первом запуске не тащить весь архив. */
    val mediaBackupSince: Long
        get() = prefs.getLong(KEY_MEDIA_SINCE, 0).takeIf { it > 0 }
            ?: (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)).also {
                prefs.edit { putLong(KEY_MEDIA_SINCE, it) }
            }

    /** Пройдена ли первая настройка (доступы, батарея, автозапуск). */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_DONE, value) }

    /** Автозапуск нельзя проверить программно — верим пользователю на слово. */
    var autostartConfirmed: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTOSTART, value) }

    var excludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED, value) }

    fun setExcluded(packageName: String, excluded: Boolean) {
        excludedPackages = if (excluded) excludedPackages + packageName else excludedPackages - packageName
    }

    /** Удаляет устаревшие записи, если срок хранения ограничен. Не чаще раза в [minIntervalMs]. */
    fun purgeIfDue(repo: NotificationRepository, minIntervalMs: Long = 0) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_PURGE, 0) < minIntervalMs) return
        prefs.edit { putLong(KEY_LAST_PURGE, now) }
        val days = retentionDays
        if (days > 0) repo.purgeOlderThan(now - TimeUnit.DAYS.toMillis(days.toLong()))
    }

    companion object {
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_ONGOING = "save_ongoing"
        private const val KEY_EXCLUDED = "excluded_packages"
        private const val KEY_LAST_PURGE = "last_purge"
        private const val KEY_MEDIA = "media_backup"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_AUTOSTART = "autostart_confirmed"
        private const val KEY_MEDIA_SINCE = "media_backup_since"

        val RETENTION_OPTIONS = listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней", 0 to "Бессрочно")
    }
}
