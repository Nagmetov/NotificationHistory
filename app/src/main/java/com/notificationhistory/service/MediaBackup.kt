package com.notificationhistory.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import com.notificationhistory.data.AppSettings
import com.notificationhistory.data.MediaDirs
import com.notificationhistory.data.MediaKind
import com.notificationhistory.data.NotificationRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Копирует входящие фото, стикеры, видео и голосовые из папок мессенджеров, пока их не удалили.
 * Когда отправитель удаляет сообщение «для всех», мессенджер стирает и файл —
 * тогда наша копия помечается как «удалено».
 *
 * Нужно разрешение «Доступ ко всем файлам» (Android 11+).
 */
object MediaBackup {

    private const val TAG = "MediaBackup"

    /** Пакет мессенджера → путь к его медиапапке относительно корня памяти. */
    private val SOURCES = listOf(
        "com.whatsapp" to "Android/media/com.whatsapp/WhatsApp/Media",
        "com.whatsapp.w4b" to "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "org.telegram.messenger" to "Android/media/org.telegram.messenger/Telegram",
        "org.telegram.messenger.web" to "Android/media/org.telegram.messenger.web/Telegram",
        "com.radolyn.ayugram" to "Android/media/com.radolyn.ayugram/AyuGram",
    )

    /** Папки с собственными отправленными файлами и служебные — не копируем. */
    private val SKIP_DIRS = setOf(
        "Sent", ".Statuses", ".Shared", ".Links", ".trash",
        "WhatsApp Profile Photos", "WallPaper", "WhatsApp AI Media", "WhatsApp Sticker Packs",
        "WhatsApp Backup Excluded Stickers", "WhatsApp Bug Report Attachments",
    )

    val packages: Set<String> = SOURCES.map { it.first }.toSet()

    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasPermission() = isSupported() && Environment.isExternalStorageManager()

    private fun roots(): List<Pair<String, File>> {
        val base = Environment.getExternalStorageDirectory()
        return SOURCES.map { (pkg, rel) -> pkg to File(base, rel) }.filter { it.second.isDirectory }
    }

    /** Папки, за которыми стоит следить FileObserver'ом (корни и их подпапки до 2 уровней). */
    fun watchDirs(): List<File> = roots().flatMap { (_, root) ->
        root.walkTopDown().maxDepth(2).onEnter { it.name !in SKIP_DIRS }.filter { it.isDirectory }.toList()
    }

    @Synchronized
    fun scan(context: Context, repo: NotificationRepository, settings: AppSettings) {
        if (!settings.mediaBackup || !hasPermission()) return
        val now = System.currentTimeMillis()
        val retention = settings.retentionDays
        val since = maxOf(
            settings.mediaBackupSince,
            if (retention > 0) now - TimeUnit.DAYS.toMillis(retention.toLong()) else 0,
        )
        val roots = roots()
        if (roots.isEmpty()) return

        for ((pkg, root) in roots) {
            val target = File(MediaDirs.backup(context), pkg).apply { mkdirs() }
            root.walkTopDown()
                .maxDepth(4)
                .onEnter { it.name !in SKIP_DIRS }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { f ->
                    val modified = f.lastModified()
                    // Совсем свежий файл может ещё докачиваться — заберём при следующем проходе.
                    if (modified < since || now - modified < 1_500 || f.length() == 0L) return@forEach
                    val size = f.length()
                    if (repo.isMediaKnown(f.absolutePath, size)) return@forEach
                    try {
                        val copy = File(target, "${modified}_${f.name}")
                        f.copyTo(copy, overwrite = true)
                        repo.insertMedia(pkg, f.absolutePath, copy.absolutePath, kindOf(f), size, modified)
                    } catch (e: Exception) {
                        Log.w(TAG, "copy failed: ${f.absolutePath}", e)
                    }
                }
        }

        // Оригиналы, которые пропали из папки мессенджера, — удалены.
        val rootPaths = roots.map { it.second.absolutePath }
        val gone = repo.mediaWithLiveSource()
            .filter { m -> rootPaths.any { m.sourcePath.startsWith(it) } && !File(m.sourcePath).exists() }
            .map { it.id }
        repo.markMediaSourceDeleted(gone, now)
    }

    fun kindOf(f: File): Int {
        val path = f.absolutePath.lowercase()
        val ext = f.extension.lowercase()
        return when {
            "sticker" in path || ext == "webp" || ext == "tgs" -> MediaKind.STICKER
            ext in setOf("jpg", "jpeg", "png", "heic", "heif", "bmp") -> MediaKind.IMAGE
            ext in setOf("mp4", "3gp", "mkv", "webm", "mov", "gif") -> MediaKind.VIDEO
            ext in setOf("opus", "ogg", "m4a", "mp3", "aac", "amr", "wav") -> MediaKind.AUDIO
            else -> MediaKind.DOCUMENT
        }
    }

    /** Следит за папками мессенджеров и зовёт [onChange] при появлении/удалении файлов. */
    fun observe(onChange: () -> Unit): FileObserver? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasPermission()) return null
        val dirs = watchDirs()
        if (dirs.isEmpty()) return null
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.CREATE
        return object : FileObserver(dirs, mask) {
            override fun onEvent(event: Int, path: String?) = onChange()
        }.also { it.startWatching() }
    }
}
