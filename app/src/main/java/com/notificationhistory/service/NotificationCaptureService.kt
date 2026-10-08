package com.notificationhistory.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.FileObserver
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationManagerCompat
import com.notificationhistory.data.AppSettings
import com.notificationhistory.data.CapturedMessage
import com.notificationhistory.data.MediaDirs
import com.notificationhistory.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Системный сервис-слушатель: Android вызывает его при появлении и удалении
 * любого уведомления, как только пользователь выдал «Доступ к уведомлениям».
 */
class NotificationCaptureService : NotificationListenerService() {

    // Один поток: события «пришло» и «удалено» должны обрабатываться строго по порядку.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val repo by lazy { NotificationRepository.get(this) }
    private val settings by lazy { AppSettings(this) }
    private var observer: FileObserver? = null
    private var scanJob: Job? = null

    override fun onListenerConnected() {
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "activeNotifications unavailable", e)
            return
        }
        scope.launch {
            repo.reconcile(active.map { it.key }.toSet())
            active.forEach(::store)
            settings.purgeIfDue(repo)
            // Обход медиапапок может занять секунды — только в фоне, не в главном потоке.
            startObserving()
            scanNow()
        }
    }

    override fun onListenerDisconnected() {
        observer?.stopWatching()
        observer = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch {
            store(sbn)
            settings.purgeIfDue(repo, minIntervalMs = TimeUnit.HOURS.toMillis(6))
        }
        // Файл вложения обычно докачивается через несколько секунд после уведомления.
        if (sbn.packageName in MediaBackup.packages) {
            scope.launch { if (observer == null) startObserving() }
            requestScan(3_000)
            scope.launch { delay(20_000); scanNow() }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        val now = System.currentTimeMillis()
        scope.launch { repo.onRemoved(sbn.key, reason, now) }
        // Удаление сообщения «для всех» часто сопровождается удалением файла.
        if (sbn.packageName in MediaBackup.packages) requestScan(2_000)
    }

    override fun onDestroy() {
        observer?.stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private fun startObserving() {
        observer?.stopWatching()
        observer = MediaBackup.observe { requestScan(1_500) }
    }

    /** Сканирование с «антидребезгом»: пачка событий файловой системы → один проход. */
    private fun requestScan(delayMs: Long) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(delayMs)
            scanNow()
        }
    }

    private fun scanNow() {
        try {
            MediaBackup.scan(this, repo, settings)
        } catch (e: Exception) {
            Log.w(TAG, "media scan failed", e)
        }
    }

    private fun store(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.packageName in settings.excludedPackages) return
        try {
            val n = NotificationParser.parse(this, sbn, settings.saveOngoing) ?: return
            val id = repo.onPosted(n)
            if (id != null && n.picture != null) savePicture(id, n.picture)
            if (n.messages.isNotEmpty()) {
                val title = n.conversationTitle ?: n.title ?: n.appLabel
                repo.processMessages(n.packageName, n.key, title, n.messages)
                    .forEach { (msgId, m) -> saveAttachment(msgId, m) }
            }
        } catch (e: Exception) {
            // Чужие уведомления бывают кривыми — одно такое не должно ронять сервис.
            Log.w(TAG, "Failed to store notification from ${sbn.packageName}", e)
        }
    }

    private fun savePicture(id: Long, bitmap: Bitmap) {
        val file = File(MediaDirs.pictures(this), "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        repo.setImagePath(id, file.absolutePath)
    }

    /**
     * Пытаемся забрать вложение сообщения по content://-ссылке.
     * Многие мессенджеры выдают доступ только системной шторке — тогда будет SecurityException,
     * и остаётся надежда на копию из папки мессенджера (MediaBackup).
     */
    private fun saveAttachment(msgId: Long, m: CapturedMessage) {
        val uri = m.uri ?: return
        try {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m.mime) ?: "bin"
            val file = File(MediaDirs.attachments(this), "$msgId.$ext")
            val input = contentResolver.openInputStream(uri) ?: return
            input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            repo.setMessageMedia(msgId, file.absolutePath)
        } catch (e: Exception) {
            Log.i(TAG, "attachment not readable: $uri (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "NotificationCapture"

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, NotificationCaptureService::class.java)

        /** Просим систему переподключить сервис, если его выгрузили (например, после обновления). */
        fun requestRebind(context: Context) {
            if (isEnabled(context)) NotificationListenerService.requestRebind(component(context))
        }
    }
}
