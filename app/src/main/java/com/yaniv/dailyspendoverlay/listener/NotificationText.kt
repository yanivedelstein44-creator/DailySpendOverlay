package com.yaniv.dailyspendoverlay.listener

import android.app.Notification
import android.os.Bundle

/** שליפת הכותרת והטקסט מתוך התראה (כל הסגנונות: רגיל, BigText, Inbox, Messaging) */
object NotificationText {

    data class Content(val title: String, val fullText: String)

    fun extract(notification: Notification): Content {
        val extras: Bundle = notification.extras
            ?: return Content("", notification.tickerText?.toString().orEmpty())

        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString().orEmpty()

        val parts = ArrayList<String>()
        parts += title

        val lastMessage = lastMessagingStyleText(extras)
        if (lastMessage != null) {
            // MessagingStyle (למשל SMS): רק ההודעה האחרונה – כדי לא לספור שוב הודעות ישנות בשיחה
            parts += lastMessage
        } else {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.let { parts += it.toString() }
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { parts += it.toString() }
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { parts += it.toString() }
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.let { parts += it.toString() }
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.let { parts += it.toString() }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts += it.toString() }
            notification.tickerText?.let { parts += it.toString() }
        }

        val fullText = parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        return Content(title, fullText)
    }

    private fun lastMessagingStyleText(extras: Bundle): String? {
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()
    }
}
