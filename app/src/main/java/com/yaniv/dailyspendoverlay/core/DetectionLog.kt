package com.yaniv.dailyspendoverlay.core

enum class DetectionStatus { COUNTED, DUPLICATE, EXCLUDED, NOT_LISTED, NO_RATE }

/**
 * רשומה ביומן הזיהוי שבמסך ההגדרות – כדי לראות אם תשלום נקלט, ולמה.
 * נשמר רק: אפליקציה, כותרת (שם בית העסק / השולח), סכום וסטטוס. גוף ההודעה לא נשמר.
 */
data class DetectionEntry(
    val timeMillis: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val amount: Amount,
    val status: DetectionStatus,
    val detail: String = "",
)

object DetectionLog {

    const val MAX_ENTRIES = 20

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun prepend(entries: List<DetectionEntry>, entry: DetectionEntry): List<DetectionEntry> =
        (listOf(entry) + entries).take(MAX_ENTRIES)

    fun encode(entries: List<DetectionEntry>): String =
        entries.joinToString(RECORD.toString()) { e ->
            listOf(
                e.timeMillis.toString(),
                clean(e.packageName),
                clean(e.appLabel),
                clean(e.title),
                e.amount.minor.toString(),
                e.amount.currency,
                e.status.name,
                clean(e.detail),
            ).joinToString(FIELD.toString())
        }

    fun decode(raw: String): List<DetectionEntry> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            val f = record.split(FIELD)
            if (f.size < 8) return@mapNotNull null
            val time = f[0].toLongOrNull() ?: return@mapNotNull null
            val minor = f[4].toLongOrNull() ?: return@mapNotNull null
            val status = DetectionStatus.entries.firstOrNull { it.name == f[6] } ?: return@mapNotNull null
            DetectionEntry(time, f[1], f[2], f[3], Amount(minor, f[5]), status, f[7])
        }
    }

    private fun clean(s: String): String =
        s.replace(FIELD, ' ').replace(RECORD, ' ').replace('\n', ' ').replace('\r', ' ').trim().take(80)
}
