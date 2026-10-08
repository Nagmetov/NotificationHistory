package com.notificationhistory.simulator

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.FileProvider
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Имитирует поведение WhatsApp для проверки «Истории уведомлений»:
 *  - одна переписка, уведомление в стиле MessagingStyle, которое переписывается целиком;
 *  - фото и стикеры кладутся в Android/media/com.whatsapp/WhatsApp/Media/…;
 *  - удаление «для всех» заменяет текст на «Это сообщение удалено» и стирает файл;
 *  - правка заменяет текст сообщения, сохраняя его время.
 *
 * Команды (adb shell am start -n com.whatsapp/com.notificationhistory.simulator.SimActivity …):
 *   --es a msg --es from Анна --es text "Привет"
 *   --es a photo --es from Анна
 *   --es a sticker --es from Анна
 *   --es a edit --es match "Привет" --es text "Привет, как дела?"
 *   --es a delete --es match "Привет"      (или без match — последнее сообщение)
 *   --es a clear
 */
class SimActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private val prefs by lazy { getSharedPreferences("sim", Context.MODE_PRIVATE) }

    private fun load(): MutableList<JSONObject> {
        val arr = JSONArray(prefs.getString("msgs", "[]"))
        return MutableList(arr.length()) { arr.getJSONObject(it) }
    }

    private fun save(list: List<JSONObject>) = prefs.edit { putString("msgs", JSONArray(list).toString()) }

    private fun handle(intent: Intent) {
        val msgs = load()
        val from = intent.getStringExtra("from") ?: "Анна"
        when (intent.getStringExtra("a")) {
            "msg" -> msgs += message(from, intent.getStringExtra("text") ?: "…")
            // --ez nouri true: файл не прикладывается к уведомлению (как бывает у настоящего WhatsApp).
            "photo" -> msgs += message(from, "📷 Фото", createMedia("WhatsApp Images", "IMG", "jpg", sticker = false))
                .apply { if (intent.getBooleanExtra("nouri", false)) put("nouri", true) }
            "sticker" -> msgs += message(from, "🏷️ Стикер", createMedia("WhatsApp Stickers", "STK", "webp", sticker = true))
            "edit" -> find(msgs, intent.getStringExtra("match"))?.put("text", intent.getStringExtra("text") ?: "")
            "delete" -> find(msgs, intent.getStringExtra("match"))?.let { m ->
                m.optString("file").takeIf { it.isNotEmpty() }?.let { File(it).delete() }
                m.put("text", "🚫 Это сообщение удалено")
                m.remove("file")
            }
            "clear" -> {
                msgs.clear()
                NotificationManagerCompat.from(this).cancelAll()
            }
        }
        save(msgs)
        if (msgs.isNotEmpty()) post(msgs)
    }

    private fun find(msgs: List<JSONObject>, match: String?): JSONObject? =
        if (match == null) msgs.lastOrNull() else msgs.lastOrNull { it.getString("text").contains(match) }

    private fun message(from: String, text: String, file: File? = null) = JSONObject().apply {
        put("from", from)
        put("time", System.currentTimeMillis())
        put("text", text)
        file?.let { put("file", it.absolutePath) }
    }

    /** Рисует картинку-заглушку и кладёт её туда, куда WhatsApp складывает входящие медиа. */
    private fun createMedia(folder: String, prefix: String, ext: String, sticker: Boolean): File {
        val dir = File(externalMediaDirs.first(), "WhatsApp/Media/$folder").apply { mkdirs() }
        val n = prefs.getInt("counter", 0) + 1
        prefs.edit { putInt("counter", n) }
        val size = if (sticker) 512 else 1024
        val bmp = Bitmap.createBitmap(size, if (sticker) size else 768, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (sticker) {
            c.drawColor(Color.TRANSPARENT)
            paint.color = Color.rgb(255, 202, 40)
            c.drawCircle(256f, 256f, 230f, paint)
            paint.color = Color.BLACK
            c.drawCircle(180f, 200f, 28f, paint)
            c.drawCircle(332f, 200f, 28f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 22f
            c.drawArc(140f, 200f, 372f, 400f, 20f, 140f, false, paint)
        } else {
            val hue = (n * 67 % 360).toFloat()
            c.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f)))
            paint.color = Color.WHITE
            paint.textSize = 96f
            c.drawText("Фото №$n", 80f, 400f, paint)
            paint.textSize = 40f
            c.drawText("симулятор WhatsApp", 84f, 470f, paint)
        }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}-WA%04d.$ext".format(n))
        file.outputStream().use {
            if (sticker) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return file
    }

    private fun post(msgs: List<JSONObject>) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("msg", "Сообщения", NotificationManager.IMPORTANCE_HIGH))
        val me = Person.Builder().setName("Вы").build()
        val style = NotificationCompat.MessagingStyle(me)
        for (m in msgs) {
            val sender = Person.Builder().setName(m.getString("from")).build()
            val msg = NotificationCompat.MessagingStyle.Message(m.getString("text"), m.getLong("time"), sender)
            m.optString("file").takeIf { it.isNotEmpty() && File(it).exists() && !m.optBoolean("nouri") }?.let { path ->
                val uri = FileProvider.getUriForFile(this, "com.whatsapp.sim.files", File(path))
                // Как настоящие мессенджеры: доступ к файлу получает только системная шторка.
                grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                msg.setData(if (path.endsWith(".webp")) "image/webp" else "image/jpeg", uri)
            }
            style.addMessage(msg)
        }
        val last = msgs.last()
        val n = NotificationCompat.Builder(this, "msg")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(last.getString("from"))
            .setContentText(last.getString("text"))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(this).notify("anna@s.whatsapp.net", 1, n)
    }
}
