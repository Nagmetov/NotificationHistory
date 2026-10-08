package com.notificationhistory.data

/** Состояние записи в истории. */
object Status {
    /** Уведомление всё ещё висит в шторке. */
    const val ACTIVE = 0

    /** Уведомление исчезло из шторки (см. [NotificationRecord.removalReason]). */
    const val REMOVED = 1

    /** Приложение заменило содержимое уведомления новым — новая версия лежит отдельной записью. */
    const val REPLACED = 2
}

enum class HistoryFilter(val title: String) {
    ALL("Все"),
    ACTIVE("Активные"),
    REMOVED("Удалённые");

    val sqlWhere: String
        get() = when (this) {
            ALL -> "1"
            ACTIVE -> "status = ${Status.ACTIVE}"
            REMOVED -> "status = ${Status.REMOVED}"
        }
}

data class NotificationRecord(
    val id: Long,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    /** Строки InboxStyle / сообщения MessagingStyle, по одной на строку. */
    val lines: String?,
    val category: String?,
    val channelId: String?,
    val postTime: Long,
    val lastUpdate: Long,
    val updateCount: Int,
    val status: Int,
    val removedTime: Long?,
    val removalReason: Int?,
    val imagePath: String? = null,
) {
    /** Самый полный доступный текст уведомления. */
    val fullText: String?
        get() = bigText?.takeIf { it.isNotBlank() } ?: text

    val preview: String
        get() = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            (lines?.lineSequence()?.lastOrNull() ?: fullText)?.takeIf { it.isNotBlank() },
        ).joinToString(": ")
}

data class AppGroup(
    val packageName: String,
    val appLabel: String,
    val count: Int,
    val removedCount: Int,
    val lastTime: Long,
    val lastPreview: String,
)

data class FilterCounts(val all: Int = 0, val active: Int = 0, val removed: Int = 0) {
    operator fun get(filter: HistoryFilter) = when (filter) {
        HistoryFilter.ALL -> all
        HistoryFilter.ACTIVE -> active
        HistoryFilter.REMOVED -> removed
    }
}

/** Состояние отдельного сообщения переписки. */
object MsgStatus {
    const val NORMAL = 0
    const val EDITED = 1
    const val DELETED = 2
}

/** Одно сообщение из уведомления-переписки (MessagingStyle). */
data class CapturedMessage(
    val sender: String?,
    val time: Long,
    val text: String?,
    val mime: String?,
    val uri: android.net.Uri?,
)

data class MessageRecord(
    val id: Long,
    val packageName: String,
    val convKey: String,
    val convTitle: String,
    val sender: String?,
    val time: Long,
    /** Текущий текст. Для удалённых — последний известный текст до удаления. */
    val text: String?,
    /** Предыдущие версии текста (для отредактированных), от старой к новой. */
    val history: List<String>,
    val status: Int,
    val changedTime: Long?,
    val mediaMime: String?,
    val mediaPath: String?,
    /** Копия медиафайла из папки мессенджера, сопоставленная по времени. */
    val linkedMedia: MediaRecord? = null,
)

data class ConversationSummary(
    val convKey: String,
    val title: String,
    val count: Int,
    val deleted: Int,
    val edited: Int,
    val lastTime: Long,
    val lastText: String,
)

object MediaKind {
    const val IMAGE = 0
    const val STICKER = 1
    const val VIDEO = 2
    const val AUDIO = 3
    const val DOCUMENT = 4

    fun label(kind: Int) = when (kind) {
        IMAGE -> "Фото"
        STICKER -> "Стикер"
        VIDEO -> "Видео"
        AUDIO -> "Аудио"
        else -> "Файл"
    }
}

data class MediaRecord(
    val id: Long,
    val packageName: String,
    val sourcePath: String,
    val localPath: String,
    val kind: Int,
    val size: Long,
    val createdTime: Long,
    /** Когда оригинал исчез из папки мессенджера (null — ещё на месте). */
    val sourceDeletedTime: Long?,
) {
    val fileName: String get() = sourcePath.substringAfterLast('/')
}

/** Сводка по удалённому контенту приложения для главного экрана. */
data class AppExtras(val deletedMessages: Int = 0, val editedMessages: Int = 0, val deletedMedia: Int = 0)

/** Данные уведомления, извлечённые из StatusBarNotification. */
data class CapturedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val lines: String?,
    val category: String?,
    val channelId: String?,
    val postTime: Long,
    /** Название переписки (для групп) или имя собеседника. */
    val conversationTitle: String? = null,
    val messages: List<CapturedMessage> = emptyList(),
    /** Картинка BigPictureStyle, если приложение её приложило. */
    val picture: android.graphics.Bitmap? = null,
) {
    val contentHash: Int
        get() = listOf(title, text, bigText, subText, lines).hashCode()
}
