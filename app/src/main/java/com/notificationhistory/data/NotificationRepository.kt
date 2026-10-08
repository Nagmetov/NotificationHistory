package com.notificationhistory.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.abs

private const val TABLE = "notifications"
private const val MESSAGES = "messages"
private const val MEDIA = "media"

/** Разделитель версий текста в столбце messages.history. */
private const val HISTORY_SEP = "\u001F"

private class NotificationDb(context: Context) :
    SQLiteOpenHelper(context, "history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sbn_key TEXT NOT NULL,
                package TEXT NOT NULL,
                app_label TEXT NOT NULL,
                title TEXT,
                text TEXT,
                big_text TEXT,
                sub_text TEXT,
                lines TEXT,
                category TEXT,
                channel_id TEXT,
                content_hash INTEGER NOT NULL,
                post_time INTEGER NOT NULL,
                last_update INTEGER NOT NULL,
                update_count INTEGER NOT NULL DEFAULT 0,
                status INTEGER NOT NULL,
                removed_time INTEGER,
                removal_reason INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_key_status ON $TABLE(sbn_key, status)")
        db.execSQL("CREATE INDEX idx_pkg_time ON $TABLE(package, post_time)")
        createV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE $TABLE ADD COLUMN image_path TEXT")
        db.execSQL(
            """
            CREATE TABLE $MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package TEXT NOT NULL,
                conv_key TEXT NOT NULL,
                conv_title TEXT NOT NULL,
                sender TEXT NOT NULL,
                msg_time INTEGER NOT NULL,
                text TEXT,
                history TEXT,
                status INTEGER NOT NULL,
                first_seen INTEGER NOT NULL,
                changed_time INTEGER,
                media_mime TEXT,
                media_path TEXT,
                UNIQUE(package, conv_key, sender, msg_time)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $MEDIA (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package TEXT NOT NULL,
                source_path TEXT NOT NULL,
                local_path TEXT NOT NULL,
                kind INTEGER NOT NULL,
                size INTEGER NOT NULL,
                created_time INTEGER NOT NULL,
                source_deleted_time INTEGER,
                UNIQUE(source_path, size)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_media_pkg_time ON $MEDIA(package, created_time)")
    }
}

/**
 * Единственная точка доступа к истории. Сервис пишет, UI читает;
 * [changes] увеличивается после каждой записи, чтобы экраны перечитывали данные.
 * Все методы блокирующие — вызывать с Dispatchers.IO.
 */
class NotificationRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = NotificationDb(appContext)
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Какие сообщения были в предыдущей версии каждой переписки (ключ — conv_key). */
    private val lastSeenMessages = HashMap<String, List<Pair<String, Long>>>()

    private fun notifyChanged() = _changes.update { it + 1 }

    // ---------- Запись уведомлений (из сервиса) ----------

    /** @return id новой записи или null, если содержимое не изменилось. */
    fun onPosted(n: CapturedNotification, now: Long = System.currentTimeMillis()): Long? {
        val w = db.writableDatabase
        var newId: Long? = null
        w.beginTransaction()
        try {
            val existing = w.rawQuery(
                "SELECT id, content_hash FROM $TABLE WHERE sbn_key = ? AND status = ${Status.ACTIVE} ORDER BY id DESC LIMIT 1",
                arrayOf(n.key),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null }

            if (existing != null && existing.second == n.contentHash) {
                // То же самое содержимое переотправлено — просто отмечаем обновление.
                w.execSQL(
                    "UPDATE $TABLE SET last_update = ?, update_count = update_count + 1 WHERE id = ?",
                    arrayOf(now, existing.first),
                )
            } else {
                if (existing != null) {
                    // Содержимое изменилось: старую версию сохраняем как «обновлено».
                    markRemoved(w, existing.first, RemovalReason.REPLACED, now, Status.REPLACED)
                }
                newId = w.insert(TABLE, null, ContentValues().apply {
                    put("sbn_key", n.key)
                    put("package", n.packageName)
                    put("app_label", n.appLabel)
                    put("title", n.title)
                    put("text", n.text)
                    put("big_text", n.bigText)
                    put("sub_text", n.subText)
                    put("lines", n.lines)
                    put("category", n.category)
                    put("channel_id", n.channelId)
                    put("content_hash", n.contentHash)
                    put("post_time", if (existing != null) now else n.postTime)
                    put("last_update", now)
                    put("status", Status.ACTIVE)
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        notifyChanged()
        return newId
    }

    fun setImagePath(id: Long, path: String) = write {
        update(TABLE, ContentValues().apply { put("image_path", path) }, "id = ?", arrayOf(id.toString()))
    }

    fun onRemoved(key: String, reason: Int, now: Long = System.currentTimeMillis()) {
        val rows = db.writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("status", Status.REMOVED)
                put("removed_time", now)
                put("removal_reason", reason)
            },
            "sbn_key = ? AND status = ${Status.ACTIVE}",
            arrayOf(key),
        )
        if (rows > 0) notifyChanged()
    }

    /**
     * Сверка после (пере)подключения сервиса: всё, что у нас числится активным,
     * но уже отсутствует в шторке, исчезло, пока мы не слушали.
     */
    fun reconcile(activeKeys: Set<String>, now: Long = System.currentTimeMillis()) {
        val w = db.writableDatabase
        val stale = w.rawQuery("SELECT id, sbn_key FROM $TABLE WHERE status = ${Status.ACTIVE}", null)
            .use { c -> buildList { while (c.moveToNext()) if (c.getString(1) !in activeKeys) add(c.getLong(0)) } }
        if (stale.isEmpty()) return
        w.beginTransaction()
        try {
            stale.forEach { markRemoved(w, it, RemovalReason.UNKNOWN, now, Status.REMOVED) }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        notifyChanged()
    }

    private fun markRemoved(w: SQLiteDatabase, id: Long, reason: Int, now: Long, status: Int) {
        w.execSQL(
            "UPDATE $TABLE SET status = ?, removed_time = ?, removal_reason = ? WHERE id = ?",
            arrayOf<Any>(status, now, reason, id),
        )
    }

    // ---------- Сообщения переписок ----------

    /**
     * Сравнивает сообщения новой версии уведомления с тем, что уже сохранено:
     * новые — добавляет, изменившийся текст — помечает как правку (старый текст в истории),
     * «Сообщение удалено» или пропажа из середины переписки — как удаление.
     *
     * @return новые сообщения с вложением (id записи + сообщение), чтобы сервис скопировал файл.
     */
    fun processMessages(
        packageName: String,
        convKey: String,
        convTitle: String,
        messages: List<CapturedMessage>,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<Long, CapturedMessage>> {
        if (messages.isEmpty()) return emptyList()
        val w = db.writableDatabase
        val withMedia = mutableListOf<Pair<Long, CapturedMessage>>()
        var changed = false
        w.beginTransaction()
        try {
            for (m in messages) {
                val sender = m.sender.orEmpty()
                val row = w.rawQuery(
                    "SELECT id, text, history, status FROM $MESSAGES WHERE package = ? AND conv_key = ? AND sender = ? AND msg_time = ?",
                    arrayOf(packageName, convKey, sender, m.time.toString()),
                ).use { c ->
                    if (c.moveToFirst()) MsgRow(c.getLong(0), c.getStringOrNull(1), c.getStringOrNull(2), c.getInt(3)) else null
                }
                if (row == null) {
                    val deletedPlaceholder = DeletedMarkers.matches(m.text)
                    val id = w.insert(MESSAGES, null, ContentValues().apply {
                        put("package", packageName)
                        put("conv_key", convKey)
                        put("conv_title", convTitle)
                        put("sender", sender)
                        put("msg_time", m.time)
                        put("text", m.text)
                        put("status", if (deletedPlaceholder) MsgStatus.DELETED else MsgStatus.NORMAL)
                        put("first_seen", now)
                        if (deletedPlaceholder) put("changed_time", now)
                        put("media_mime", m.mime)
                    })
                    if (id > 0 && m.uri != null) withMedia += id to m
                    changed = true
                } else if (row.status != MsgStatus.DELETED && m.text != row.text) {
                    if (DeletedMarkers.matches(m.text)) {
                        // Текст оставляем прежним — ради него всё и затевалось.
                        w.execSQL(
                            "UPDATE $MESSAGES SET status = ?, changed_time = ? WHERE id = ?",
                            arrayOf<Any>(MsgStatus.DELETED, now, row.id),
                        )
                    } else {
                        val history = listOfNotNull(row.history, row.text).joinToString(HISTORY_SEP)
                        w.execSQL(
                            "UPDATE $MESSAGES SET text = ?, history = ?, status = ?, changed_time = ? WHERE id = ?",
                            arrayOf<Any?>(m.text, history, MsgStatus.EDITED, now, row.id),
                        )
                    }
                    changed = true
                }
            }

            // Сообщение пропало из переписки, хотя более старые остались, — его удалили.
            // Пропажу самых старых не считаем: мессенджеры обрезают длинные переписки сверху.
            val newKeys = messages.map { it.sender.orEmpty() to it.time }
            val oldest = messages.minOf { it.time }
            lastSeenMessages[convKey]?.forEach { (sender, time) ->
                if (time >= oldest && (sender to time) !in newKeys) {
                    w.execSQL(
                        "UPDATE $MESSAGES SET status = ?, changed_time = ? WHERE package = ? AND conv_key = ? AND sender = ? AND msg_time = ? AND status != ?",
                        arrayOf<Any>(MsgStatus.DELETED, now, packageName, convKey, sender, time, MsgStatus.DELETED),
                    )
                    changed = true
                }
            }
            lastSeenMessages[convKey] = newKeys
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        if (changed) notifyChanged()
        return withMedia
    }

    private class MsgRow(val id: Long, val text: String?, val history: String?, val status: Int)

    fun setMessageMedia(id: Long, path: String) = write {
        update(MESSAGES, ContentValues().apply { put("media_path", path) }, "id = ?", arrayOf(id.toString()))
    }

    fun conversations(packageName: String): List<ConversationSummary> = db.readableDatabase.rawQuery(
        """
        SELECT conv_key, conv_title, COUNT(*),
               COALESCE(SUM(status = ${MsgStatus.DELETED}), 0),
               COALESCE(SUM(status = ${MsgStatus.EDITED}), 0),
               MAX(msg_time), sender, text
        FROM $MESSAGES WHERE package = ?
        GROUP BY conv_key ORDER BY MAX(msg_time) DESC
        """.trimIndent(),
        arrayOf(packageName),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val sender = c.getString(6).takeIf { it.isNotEmpty() }
                val text = c.getStringOrNull(7).orEmpty()
                add(
                    ConversationSummary(
                        convKey = c.getString(0),
                        title = c.getString(1),
                        count = c.getInt(2),
                        deleted = c.getInt(3),
                        edited = c.getInt(4),
                        lastTime = c.getLong(5),
                        lastText = if (sender != null) "$sender: $text" else text,
                    )
                )
            }
        }
    }

    fun messages(packageName: String, convKey: String): List<MessageRecord> {
        val list = db.readableDatabase.rawQuery(
            "SELECT * FROM $MESSAGES WHERE package = ? AND conv_key = ? ORDER BY msg_time, id",
            arrayOf(packageName, convKey),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMessage()) } }
        return linkMedia(packageName, list)
    }

    /**
     * Сопоставляет сообщения вида «📷 Фото» / «Стикер» с копиями файлов из папки мессенджера:
     * файл обычно появляется в течение пары минут после прихода сообщения.
     */
    private fun linkMedia(packageName: String, list: List<MessageRecord>): List<MessageRecord> {
        val candidates = list.filter { it.mediaPath == null && MediaHints.kindOf(it.text) != null }
        if (candidates.isEmpty()) return list
        val pool = db.readableDatabase.rawQuery(
            "SELECT * FROM $MEDIA WHERE package = ? AND created_time BETWEEN ? AND ? ORDER BY created_time",
            arrayOf(
                packageName,
                (candidates.minOf { it.time } - 30_000).toString(),
                (candidates.maxOf { it.time } + 180_000).toString(),
            ),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toMedia()) } }.toMutableList()
        if (pool.isEmpty()) return list

        val linked = HashMap<Long, MediaRecord>()
        for (m in candidates) {
            val kind = MediaHints.kindOf(m.text)
            val best = pool
                .filter { it.kind == kind && it.createdTime in (m.time - 30_000)..(m.time + 180_000) }
                .minByOrNull { abs(it.createdTime - m.time) }
                ?: continue
            pool.remove(best)
            linked[m.id] = best
        }
        return list.map { m -> linked[m.id]?.let { m.copy(linkedMedia = it) } ?: m }
    }

    // ---------- Копии медиафайлов ----------

    fun isMediaKnown(sourcePath: String, size: Long): Boolean = db.readableDatabase.rawQuery(
        "SELECT 1 FROM $MEDIA WHERE source_path = ? AND size = ? LIMIT 1",
        arrayOf(sourcePath, size.toString()),
    ).use { it.moveToFirst() }

    fun insertMedia(packageName: String, sourcePath: String, localPath: String, kind: Int, size: Long, createdTime: Long) {
        db.writableDatabase.insertWithOnConflict(MEDIA, null, ContentValues().apply {
            put("package", packageName)
            put("source_path", sourcePath)
            put("local_path", localPath)
            put("kind", kind)
            put("size", size)
            put("created_time", createdTime)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        notifyChanged()
    }

    /** Файлы, оригинал которых ещё должен лежать в папке мессенджера. */
    fun mediaWithLiveSource(): List<MediaRecord> = db.readableDatabase.rawQuery(
        "SELECT * FROM $MEDIA WHERE source_deleted_time IS NULL", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMedia()) } }

    fun markMediaSourceDeleted(ids: List<Long>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        db.writableDatabase.execSQL(
            "UPDATE $MEDIA SET source_deleted_time = ? WHERE id IN (${ids.joinToString(",")})",
            arrayOf(now),
        )
        notifyChanged()
    }

    fun media(packageName: String, onlyDeleted: Boolean): List<MediaRecord> = db.readableDatabase.rawQuery(
        "SELECT * FROM $MEDIA WHERE package = ?" +
            (if (onlyDeleted) " AND source_deleted_time IS NOT NULL" else "") +
            " ORDER BY created_time DESC",
        arrayOf(packageName),
    ).use { c -> buildList { while (c.moveToNext()) add(c.toMedia()) } }

    fun mediaById(id: Long): MediaRecord? = db.readableDatabase.rawQuery(
        "SELECT * FROM $MEDIA WHERE id = ?", arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) c.toMedia() else null }

    // ---------- Чтение уведомлений (для UI) ----------

    fun counts(): FilterCounts = db.readableDatabase.rawQuery(
        """
        SELECT COUNT(*),
               COALESCE(SUM(status = ${Status.ACTIVE}), 0),
               COALESCE(SUM(status = ${Status.REMOVED}), 0)
        FROM $TABLE
        """.trimIndent(),
        null,
    ).use { c -> c.moveToFirst(); FilterCounts(c.getInt(0), c.getInt(1), c.getInt(2)) }

    fun groups(filter: HistoryFilter): List<AppGroup> = db.readableDatabase.rawQuery(
        // SQLite возвращает «голые» столбцы из строки, на которой достигнут MAX().
        """
        SELECT package, app_label, COUNT(*), COALESCE(SUM(status = ${Status.REMOVED}), 0),
               MAX(post_time), title, text, big_text, lines
        FROM $TABLE WHERE ${filter.sqlWhere}
        GROUP BY package ORDER BY MAX(post_time) DESC
        """.trimIndent(),
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val preview = listOfNotNull(
                    c.getStringOrNull(5)?.takeIf { it.isNotBlank() },
                    (c.getStringOrNull(8)?.lineSequence()?.lastOrNull()
                        ?: c.getStringOrNull(7)?.takeIf { it.isNotBlank() }
                        ?: c.getStringOrNull(6))?.takeIf { it.isNotBlank() },
                ).joinToString(": ")
                add(AppGroup(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4), preview))
            }
        }
    }

    /** Удалённые/изменённые сообщения и удалённые медиа по каждому приложению. */
    fun appExtras(): Map<String, AppExtras> {
        val r = db.readableDatabase
        val map = HashMap<String, AppExtras>()
        r.rawQuery(
            "SELECT package, COALESCE(SUM(status = ${MsgStatus.DELETED}), 0), COALESCE(SUM(status = ${MsgStatus.EDITED}), 0) FROM $MESSAGES GROUP BY package",
            null,
        ).use { c -> while (c.moveToNext()) map[c.getString(0)] = AppExtras(c.getInt(1), c.getInt(2)) }
        r.rawQuery(
            "SELECT package, COUNT(*) FROM $MEDIA WHERE source_deleted_time IS NOT NULL GROUP BY package",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(0)
                map[pkg] = (map[pkg] ?: AppExtras()).copy(deletedMedia = c.getInt(1))
            }
        }
        return map
    }

    fun hasChats(packageName: String): Boolean = exists("SELECT 1 FROM $MESSAGES WHERE package = ? LIMIT 1", packageName)

    fun hasMedia(packageName: String): Boolean = exists("SELECT 1 FROM $MEDIA WHERE package = ? LIMIT 1", packageName)

    private fun exists(sql: String, vararg args: String) = db.readableDatabase.rawQuery(sql, args).use { it.moveToFirst() }

    fun forPackage(packageName: String, filter: HistoryFilter): List<NotificationRecord> =
        query("package = ? AND ${filter.sqlWhere}", arrayOf(packageName))

    fun byId(id: Long): NotificationRecord? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    /** Все приложения, когда-либо попадавшие в историю (для экрана исключений). */
    fun knownApps(): List<Pair<String, String>> = db.readableDatabase.rawQuery(
        "SELECT package, app_label FROM $TABLE GROUP BY package ORDER BY app_label COLLATE NOCASE",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    private fun query(where: String, args: Array<String>): List<NotificationRecord> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $where ORDER BY post_time DESC, id DESC",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toRecord()) } }

    // ---------- Удаление ----------

    fun delete(id: Long) {
        byId(id)?.imagePath?.let { File(it).delete() }
        write { delete(TABLE, "id = ?", arrayOf(id.toString())) }
    }

    fun deletePackage(packageName: String) {
        deleteFiles("SELECT image_path FROM $TABLE WHERE package = ?", packageName)
        deleteFiles("SELECT media_path FROM $MESSAGES WHERE package = ?", packageName)
        deleteFiles("SELECT local_path FROM $MEDIA WHERE package = ?", packageName)
        write {
            delete(TABLE, "package = ?", arrayOf(packageName)) +
                delete(MESSAGES, "package = ?", arrayOf(packageName)) +
                delete(MEDIA, "package = ?", arrayOf(packageName))
        }
    }

    fun deleteAll() {
        MediaDirs.all(appContext).forEach { it.deleteRecursively() }
        lastSeenMessages.clear()
        write { delete(TABLE, null, null) + delete(MESSAGES, null, null) + delete(MEDIA, null, null) }
    }

    /** Удаляет записи и файлы старше [cutoff]. Активные уведомления не трогаем. */
    fun purgeOlderThan(cutoff: Long) {
        val c = cutoff.toString()
        deleteFiles("SELECT image_path FROM $TABLE WHERE post_time < ? AND status != ${Status.ACTIVE}", c)
        deleteFiles("SELECT media_path FROM $MESSAGES WHERE msg_time < ?", c)
        deleteFiles("SELECT local_path FROM $MEDIA WHERE created_time < ?", c)
        write {
            delete(TABLE, "post_time < ? AND status != ${Status.ACTIVE}", arrayOf(c)) +
                delete(MESSAGES, "msg_time < ?", arrayOf(c)) +
                delete(MEDIA, "created_time < ?", arrayOf(c))
        }
    }

    private fun deleteFiles(sql: String, vararg args: String) {
        db.readableDatabase.rawQuery(sql, args).use { cur ->
            while (cur.moveToNext()) cur.getStringOrNull(0)?.let { File(it).delete() }
        }
    }

    private inline fun write(block: SQLiteDatabase.() -> Int) {
        if (db.writableDatabase.block() > 0) notifyChanged()
    }

    companion object {
        @Volatile
        private var instance: NotificationRepository? = null

        fun get(context: Context): NotificationRepository =
            instance ?: synchronized(this) {
                instance ?: NotificationRepository(context).also { instance = it }
            }
    }
}

/** Где приложение хранит свои копии картинок и файлов. */
object MediaDirs {
    fun pictures(context: Context) = File(context.filesDir, "pictures").apply { mkdirs() }
    fun attachments(context: Context) = File(context.filesDir, "attachments").apply { mkdirs() }
    fun backup(context: Context) = File(context.filesDir, "backup").apply { mkdirs() }
    fun all(context: Context) = listOf(pictures(context), attachments(context), backup(context))
}

/** Тексты, которыми мессенджеры заменяют удалённое сообщение. */
object DeletedMarkers {
    private val phrases = listOf(
        "this message was deleted",
        "you deleted this message",
        "это сообщение удалено",
        "сообщение удалено",
        "вы удалили это сообщение",
        "message deleted",
        "deleted message",
        "удалённое сообщение",
        "удаленное сообщение",
    )

    fun matches(text: String?): Boolean {
        val t = text?.lowercase()?.trim()?.trimStart { !it.isLetter() } ?: return false
        return t.length < 40 && phrases.any { t.startsWith(it) }
    }
}

/** Распознаёт подписи медиа-сообщений («📷 Фото», «Стикер», «Photo»…). */
object MediaHints {
    private val patterns = listOf(
        MediaKind.STICKER to Regex("стикер|sticker", RegexOption.IGNORE_CASE),
        MediaKind.IMAGE to Regex("фото|photo|изображени|image|📷", RegexOption.IGNORE_CASE),
        MediaKind.VIDEO to Regex("видео|video|gif|🎥|📹", RegexOption.IGNORE_CASE),
        MediaKind.AUDIO to Regex("голосов|аудио|voice|audio|🎤|🎵", RegexOption.IGNORE_CASE),
        MediaKind.DOCUMENT to Regex("документ|document|файл|file|📄", RegexOption.IGNORE_CASE),
    )

    fun kindOf(text: String?): Int? {
        if (text == null || text.length > 60) return null
        return patterns.firstOrNull { it.second.containsMatchIn(text) }?.first
    }
}

private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

private fun Cursor.str(name: String) = getStringOrNull(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

private fun Cursor.toRecord() = NotificationRecord(
    id = long("id")!!,
    key = str("sbn_key")!!,
    packageName = str("package")!!,
    appLabel = str("app_label")!!,
    title = str("title"),
    text = str("text"),
    bigText = str("big_text"),
    subText = str("sub_text"),
    lines = str("lines"),
    category = str("category"),
    channelId = str("channel_id"),
    postTime = long("post_time")!!,
    lastUpdate = long("last_update")!!,
    updateCount = long("update_count")!!.toInt(),
    status = long("status")!!.toInt(),
    removedTime = long("removed_time"),
    removalReason = long("removal_reason")?.toInt(),
    imagePath = str("image_path"),
)

private fun Cursor.toMessage() = MessageRecord(
    id = long("id")!!,
    packageName = str("package")!!,
    convKey = str("conv_key")!!,
    convTitle = str("conv_title")!!,
    sender = str("sender")!!.takeIf { it.isNotEmpty() },
    time = long("msg_time")!!,
    text = str("text"),
    history = str("history")?.split(HISTORY_SEP).orEmpty(),
    status = long("status")!!.toInt(),
    changedTime = long("changed_time"),
    mediaMime = str("media_mime"),
    mediaPath = str("media_path"),
)

private fun Cursor.toMedia() = MediaRecord(
    id = long("id")!!,
    packageName = str("package")!!,
    sourcePath = str("source_path")!!,
    localPath = str("local_path")!!,
    kind = long("kind")!!.toInt(),
    size = long("size")!!,
    createdTime = long("created_time")!!,
    sourceDeletedTime = long("source_deleted_time"),
)
