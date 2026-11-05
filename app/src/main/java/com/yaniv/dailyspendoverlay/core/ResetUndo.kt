package com.yaniv.dailyspendoverlay.core

/** מה שאופס היום – כדי שאפשר יהיה לשחזר עד סוף היום */
data class ResetBackup(
    val day: String,
    val totalMinor: Long,
    val count: Int,
    val lastTx: Amount?,
    val lastTxHomeMinor: Long,
)

/**
 * כפתור "שחזר את ההוצאות של היום".
 *  • לפני איפוס שומרים גיבוי. איפוס שני באותו יום מצטבר לגיבוי הקיים.
 *  • שחזור מוסיף את מה שאופס לסכום הנוכחי, כך שתשלומים שנספרו אחרי האיפוס לא הולכים לאיבוד.
 *  • גיבוי של יום אחר לא משוחזר.
 */
object ResetUndo {

    fun backup(current: LedgerState, existing: ResetBackup?, today: String): ResetBackup? {
        val sameDay = existing?.takeIf { it.day == today }
        if (current.day != today || (current.totalMinor == 0L && current.count == 0)) return sameDay
        return ResetBackup(
            day = today,
            totalMinor = (sameDay?.totalMinor ?: 0L) + current.totalMinor,
            count = (sameDay?.count ?: 0) + current.count,
            lastTx = current.lastTx ?: sameDay?.lastTx,
            lastTxHomeMinor = if (current.lastTx != null) current.lastTxHomeMinor else sameDay?.lastTxHomeMinor ?: 0L,
        )
    }

    fun canRestore(backup: ResetBackup?, today: String): Boolean =
        backup != null && backup.day == today && (backup.totalMinor > 0L || backup.count > 0)

    /** null = אין מה לשחזר */
    fun restore(current: LedgerState, backup: ResetBackup?, today: String): LedgerState? {
        if (!canRestore(backup, today)) return null
        backup!!
        val base = DailyLedger.rollover(current, today)
        return base.copy(
            totalMinor = base.totalMinor + backup.totalMinor,
            count = base.count + backup.count,
            lastTx = base.lastTx ?: backup.lastTx,
            lastTxHomeMinor = if (base.lastTx != null) base.lastTxHomeMinor else backup.lastTxHomeMinor,
        )
    }
}
