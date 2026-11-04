package com.yaniv.dailyspendoverlay.net

import android.content.Context
import com.yaniv.dailyspendoverlay.core.FxRates
import com.yaniv.dailyspendoverlay.core.FxTable
import com.yaniv.dailyspendoverlay.data.SpendStore
import java.io.IOException

/**
 * רענון שערי המטבע היומיים מהבנק המרכזי האירופי – רק אם המשתמש אישר, ורק כשפותחים את האפליקציה
 * (אין רענון ברקע). בלי אישור משתמשים בשערים המשוערים המובנים.
 */
object FxUpdater {

    private const val REFRESH_INTERVAL_MS = 12L * 60 * 60 * 1000

    fun isStale(store: SpendStore, nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - store.fxFetchedAtMillis > REFRESH_INTERVAL_MS

    @Throws(IOException::class)
    fun refresh(context: Context): FxTable {
        val xml = OnlineGateway.getText(context, OnlinePurpose.FX_RATES, FxRates.ECB_DAILY_URL)
        val table = FxRates.parseEcbXml(xml) ?: throw IOException("Unexpected ECB format")
        SpendStore.get(context).saveOnlineFx(table)
        return table
    }
}
