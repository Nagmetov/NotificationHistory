package com.notificationhistory.service

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.notificationhistory.data.CapturedMessage
import com.notificationhistory.data.CapturedNotification

object NotificationParser {

    /**
     * Извлекает текст уведомления. Возвращает null для того, что не стоит хранить:
     * сводки групп (дублируют дочерние уведомления), постоянные уведомления
     * (если выключены в настройках) и уведомления без текста.
     */
    fun parse(context: Context, sbn: StatusBarNotification, saveOngoing: Boolean): CapturedNotification? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (!saveOngoing && (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) return null

        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = (extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString()

        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val selfName = messaging?.user?.name?.toString()
        val messages = messaging?.messages.orEmpty().map { m ->
            CapturedMessage(
                // Сообщения без отправителя — это ответы самого владельца телефона.
                sender = m.person?.name?.toString() ?: selfName ?: "Вы",
                time = m.timestamp,
                text = m.text?.toString(),
                mime = m.dataMimeType,
                uri = m.dataUri,
            )
        }
        val conversationTitle = messaging?.conversationTitle?.toString()
            ?: title?.takeIf { messages.isNotEmpty() }

        val lines = if (messages.isNotEmpty()) {
            val isGroup = messaging?.isGroupConversation == true
            messages.joinToString("\n") { m ->
                val body = m.text ?: m.mime?.let { "[вложение: $it]" }.orEmpty()
                if (m.sender != null && (isGroup || m.sender != title)) "${m.sender}: $body" else body
            }
        } else {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString() }
        }?.takeIf { it.isNotBlank() }

        val picture = extractPicture(context, extras)

        if (listOf(title, text, bigText, lines).all { it.isNullOrBlank() } && picture == null) return null

        return CapturedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            bigText = bigText?.takeIf { it != text },
            subText = subText,
            lines = lines,
            category = n.category,
            channelId = n.channelId,
            postTime = sbn.postTime,
            conversationTitle = conversationTitle,
            messages = messages,
            picture = picture,
        )
    }

    /** Картинка BigPictureStyle: в старых версиях — Bitmap, с Android 12 может быть Icon. */
    @Suppress("DEPRECATION")
    private fun extractPicture(context: Context, extras: Bundle): Bitmap? = try {
        (extras.get(Notification.EXTRA_PICTURE) as? Bitmap)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (extras.get(Notification.EXTRA_PICTURE_ICON) as? Icon)?.loadDrawable(context)?.toBitmap()
            } else null
    } catch (_: Exception) {
        null
    }

    private fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
