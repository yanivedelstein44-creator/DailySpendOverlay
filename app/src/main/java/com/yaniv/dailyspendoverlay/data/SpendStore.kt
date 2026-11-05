package com.yaniv.dailyspendoverlay.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.telephony.TelephonyManager
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.Conversion
import com.yaniv.dailyspendoverlay.core.Currencies
import com.yaniv.dailyspendoverlay.core.WorkTime
import com.yaniv.dailyspendoverlay.core.DailyLedger
import com.yaniv.dailyspendoverlay.core.DetectionEntry
import com.yaniv.dailyspendoverlay.core.DetectionLog
import com.yaniv.dailyspendoverlay.core.FxRates
import com.yaniv.dailyspendoverlay.core.FxTable
import com.yaniv.dailyspendoverlay.core.LedgerResult
import com.yaniv.dailyspendoverlay.core.LedgerState
import com.yaniv.dailyspendoverlay.core.SourceRules
import com.yaniv.dailyspendoverlay.core.ThemeColor
import com.yaniv.dailyspendoverlay.core.Themes
import com.yaniv.dailyspendoverlay.net.OnlinePurpose
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

/**
 * זיכרון מקומי (SharedPreferences) – הכל נשמר רק בטלפון.
 *
 * שומר: הסכום המצטבר של היום + התאריך, מספר עסקאות, העסקה האחרונה, חלון הכפילויות,
 * התקציב היומי והמטבע הראשי, כללי המקורות, יומן הזיהוי, שערי מטבע והסכמות לאינטרנט.
 *
 * כל הקריאות מגיעות מה-main thread (שירות ההתראות + המסך), ולכן אין מרוצי נתונים;
 * בכל זאת הפעולות מסונכרנות ליתר ביטחון.
 */
class SpendStore private constructor(val preferences: SharedPreferences) {

    companion object {
        const val PREFS_NAME = "daily_spend"

        private const val KEY_DAY = "day"
        private const val KEY_TOTAL = "total_minor"
        private const val KEY_COUNT = "count"
        private const val KEY_LAST_TX_MINOR = "last_tx_minor"
        private const val KEY_LAST_TX_CURRENCY = "last_tx_currency"
        private const val KEY_LAST_TX_HOME = "last_tx_home_minor"
        private const val KEY_RECENT = "recent_amounts"
        private const val KEY_FINGERPRINTS = "fingerprints"
        private const val KEY_BUDGET = "budget_minor"
        private const val KEY_HOME_CURRENCY = "home_currency"
        private const val KEY_RULES = "source_rules"
        private const val KEY_LOG = "detection_log"
        private const val KEY_FX_TABLE = "fx_table"
        private const val KEY_FX_FETCHED_AT = "fx_fetched_at"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_EXCLUDE_INSTALLMENTS = "exclude_installments"
        private const val KEY_THEME = "theme_color"
        private const val KEY_BRIGHTNESS = "theme_brightness"
        private const val KEY_OVERLAY_FULL = "overlay_full"
        private const val KEY_ON_UNLOCK = "show_on_unlock"
        private const val KEY_WAGE = "hourly_wage_minor"
        private const val KEY_WAGE_MONTHLY = "monthly_wage_minor"
        private const val KEY_WORK_HOURS = "work_hours_month"

        /** ברירת מחדל: אין תקציב (0). המשתמש מגדיר אם רוצה */
        const val DEFAULT_BUDGET_MINOR = 0L

        @Volatile
        private var instance: SpendStore? = null

        fun get(context: Context): SpendStore =
            instance ?: synchronized(this) {
                instance ?: SpendStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also {
                    it.ensureHomeCurrency(context.applicationContext)
                    instance = it
                }
            }

        /** היום לפי שעון הטלפון (אזור הזמן של המכשיר) */
        fun today(): String = LocalDate.now().toString()

        /** מטבע לפי מדינת ה-SIM/הרשת (ישראל → ₪), ואם אין – לפי הגדרות השפה והאזור */
        private fun deviceCurrency(context: Context): String? {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val iso = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso)
                .map { it.trim() }
                .firstOrNull { it.length == 2 }
            val fromCountry = iso?.let {
                try {
                    Currency.getInstance(Locale.Builder().setRegion(it.uppercase(Locale.ROOT)).build())?.currencyCode
                } catch (e: RuntimeException) {
                    null
                }
            }
            if (fromCountry != null) return fromCountry
            return try {
                Currency.getInstance(Locale.getDefault())?.currencyCode
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // חישוב יומי
    // ------------------------------------------------------------------

    @Synchronized
    fun loadState(): LedgerState {
        val lastCurrency = preferences.getString(KEY_LAST_TX_CURRENCY, null)
        val lastMinor = preferences.getLong(KEY_LAST_TX_MINOR, 0L)
        return LedgerState(
            day = preferences.getString(KEY_DAY, "") ?: "",
            totalMinor = preferences.getLong(KEY_TOTAL, 0L),
            count = preferences.getInt(KEY_COUNT, 0),
            lastTx = if (lastCurrency != null && lastMinor > 0) Amount(lastMinor, lastCurrency) else null,
            lastTxHomeMinor = preferences.getLong(KEY_LAST_TX_HOME, 0L),
            recentAmounts = DailyLedger.decodeRecent(preferences.getString(KEY_RECENT, "") ?: ""),
            fingerprints = (preferences.getString(KEY_FINGERPRINTS, "") ?: "")
                .split('\n').filter { it.isNotBlank() },
        )
    }

    @SuppressLint("ApplySharedPref") // commit מכוון: הסכום נשמר לדיסק לפני שהחלונית מוצגת
    @Synchronized
    private fun saveState(state: LedgerState) {
        val editor = preferences.edit()
            .putString(KEY_DAY, state.day)
            .putLong(KEY_TOTAL, state.totalMinor)
            .putInt(KEY_COUNT, state.count)
            .putLong(KEY_LAST_TX_HOME, state.lastTxHomeMinor)
            .putString(KEY_RECENT, DailyLedger.encodeRecent(state.recentAmounts))
            .putString(KEY_FINGERPRINTS, state.fingerprints.joinToString("\n"))
        val last = state.lastTx
        if (last == null) {
            editor.remove(KEY_LAST_TX_MINOR).remove(KEY_LAST_TX_CURRENCY)
        } else {
            editor.putLong(KEY_LAST_TX_MINOR, last.minor).putString(KEY_LAST_TX_CURRENCY, last.currency)
        }
        editor.commit() // סינכרוני: הסכום נשמר לדיסק לפני שהחלונית מוצגת
    }

    /**
     * רושם עסקה: מאפס אם התחלף היום, מסנן כפילויות (10 דקות), מוסיף לסכום ושומר.
     * @param homeMinor הסכום אחרי המרה למטבע הראשי
     */
    @Synchronized
    fun registerTransaction(
        original: Amount,
        homeMinor: Long,
        fingerprint: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): LedgerResult {
        val result = DailyLedger.register(loadState(), original, homeMinor, fingerprint, nowMillis, today())
        saveState(result.state)
        return result
    }

    /** עסקה שלא נספרת (עסקה בתשלומים כשהמתג "לא לכלול" דלוק) – ראה DailyLedger.exclude */
    @Synchronized
    fun excludeTransaction(
        original: Amount,
        fingerprint: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): LedgerResult {
        val result = DailyLedger.exclude(loadState(), original, fingerprint, nowMillis, today())
        saveState(result.state)
        return result
    }

    /** צבע החלונית (כחול / טורקיז / ירוק / סגול / ורוד / אפור) */
    var themeColor: ThemeColor
        get() = Themes.byId(preferences.getString(KEY_THEME, Themes.DEFAULT_ID))
        set(value) {
            preferences.edit().putString(KEY_THEME, value.id).apply()
        }

    /** שכר שעתי במטבע הראשי (אגורות/סנטים) לחישוב "זמן עבודה". 0 = כבוי (ברירת מחדל) */
    var hourlyWageMinor: Long
        get() = preferences.getLong(KEY_WAGE, 0L)
        set(value) {
            preferences.edit().putLong(KEY_WAGE, value.coerceAtLeast(0L)).apply()
        }

    /** שכר חודשי שהמשתמש הזין (0 = הוזן שכר שעתי ישירות). השכר השעתי נגזר ממנו */
    var monthlyWageMinor: Long
        get() = preferences.getLong(KEY_WAGE_MONTHLY, 0L)
        set(value) {
            preferences.edit().putLong(KEY_WAGE_MONTHLY, value.coerceAtLeast(0L)).apply()
        }

    /** כמה שעות עובדים בחודש (לחלוקת שכר חודשי). ברירת מחדל 182 = משרה מלאה */
    var workHoursPerMonth: Long
        get() = preferences.getLong(KEY_WORK_HOURS, WorkTime.MONTHLY_HOURS)
        set(value) {
            preferences.edit().putLong(KEY_WORK_HOURS, value.coerceIn(1L, 744L)).apply()
        }

    /** גודל החלונית: false = כרטיס, true = מסך מלא */
    var overlayFull: Boolean
        get() = preferences.getBoolean(KEY_OVERLAY_FULL, false)
        set(value) {
            preferences.edit().putBoolean(KEY_OVERLAY_FULL, value).apply()
        }

    /** להציג את הסכום היומי גם כשפותחים את הטלפון (אחרי שחרור הנעילה). כבוי כברירת מחדל */
    var showOnUnlock: Boolean
        get() = preferences.getBoolean(KEY_ON_UNLOCK, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ON_UNLOCK, value).apply()
        }

    /** בהירות החלונית באחוזים (0–100, ברירת מחדל 50) */
    var brightnessPercent: Int
        get() = preferences.getInt(KEY_BRIGHTNESS, Themes.DEFAULT_BRIGHTNESS).coerceIn(0, 100)
        set(value) {
            preferences.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, 100)).apply()
        }

    /** מתג "להציג רק הוצאות יומיות": עסקאות בתשלומים לא נספרות בסכום היומי (ברירת מחדל: דלוק) */
    var excludeInstallments: Boolean
        get() = preferences.getBoolean(KEY_EXCLUDE_INSTALLMENTS, true)
        set(value) {
            preferences.edit().putBoolean(KEY_EXCLUDE_INSTALLMENTS, value).apply()
        }

    /** מצב היום להצגה. אם התאריך התחלף ועוד לא היו עסקאות – מחזיר 0. */
    fun todaySnapshot(): LedgerState = DailyLedger.rollover(loadState(), today())

    @Synchronized
    fun resetToday() {
        saveState(loadState().copy(day = today(), totalMinor = 0, count = 0, lastTx = null, lastTxHomeMinor = 0))
    }

    // ------------------------------------------------------------------
    // תקציב ומטבע ראשי
    // ------------------------------------------------------------------

    /** התקציב היומי, במטבע הראשי (אגורות/סנטים). 0 = בלי תקציב */
    var budgetMinor: Long
        get() = preferences.getLong(KEY_BUDGET, DEFAULT_BUDGET_MINOR)
        set(value) {
            preferences.edit().putLong(KEY_BUDGET, value).apply()
        }

    /** המטבע הראשי (ISO). נקבע בהפעלה הראשונה לפי מדינת הטלפון, ואם לא נתמך – ₪. */
    val homeCurrency: String
        get() = preferences.getString(KEY_HOME_CURRENCY, null)
            ?.takeIf { Currencies.isSupported(it) }
            ?: "ILS"

    private fun ensureHomeCurrency(context: Context) {
        if (preferences.getString(KEY_HOME_CURRENCY, null) != null) return
        preferences.edit().putString(KEY_HOME_CURRENCY, Currencies.defaultFor(deviceCurrency(context))).apply()
    }

    /**
     * החלפת מטבע ראשי: התקציב והסכום של היום מומרים לפי השערים הטובים ביותר שיש.
     * אם אין שער – התקציב נשאר כמספר והסכום של היום מתאפס.
     */
    @Synchronized
    fun changeHomeCurrency(newCode: String) {
        val old = homeCurrency
        if (newCode == old || !Currencies.isSupported(newCode)) return
        val table = onlineFxTable()
        fun convert(minor: Long): Long? =
            FxRates.convert(Amount(minor, old), newCode, table)?.homeMinor

        val newBudget = convert(budgetMinor)
        val state = todaySnapshot()
        val newTotal = convert(state.totalMinor)
        val newLastHome = convert(state.lastTxHomeMinor)

        preferences.edit()
            .putString(KEY_HOME_CURRENCY, newCode)
            .putLong(KEY_BUDGET, newBudget?.let { roundToWhole(it) } ?: budgetMinor)
            .putLong(KEY_WAGE, convert(hourlyWageMinor)?.let { roundToWhole(it) } ?: hourlyWageMinor)
            .putLong(KEY_WAGE_MONTHLY, convert(monthlyWageMinor)?.let { roundToWhole(it) } ?: monthlyWageMinor)
            .apply()
        if (newTotal != null && newLastHome != null) {
            saveState(state.copy(totalMinor = newTotal, lastTxHomeMinor = newLastHome))
        } else {
            saveState(state.copy(totalMinor = 0, count = 0, lastTx = null, lastTxHomeMinor = 0))
        }
    }

    private fun roundToWhole(minor: Long): Long = Math.round(minor / 100.0) * 100

    /** המרה של עסקה למטבע הראשי (null אם אין שום שער למטבע הזה) */
    fun convertToHome(amount: Amount): Conversion? = FxRates.convert(amount, homeCurrency, onlineFxTable())

    // ------------------------------------------------------------------
    // שערי מטבע מהרשת (רק אם המשתמש אישר)
    // ------------------------------------------------------------------

    fun onlineFxTable(): FxTable? = preferences.getString(KEY_FX_TABLE, null)?.let { FxRates.decode(it) }

    fun saveOnlineFx(table: FxTable, fetchedAtMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putString(KEY_FX_TABLE, FxRates.encode(table))
            .putLong(KEY_FX_FETCHED_AT, fetchedAtMillis)
            .apply()
    }

    val fxFetchedAtMillis: Long get() = preferences.getLong(KEY_FX_FETCHED_AT, 0L)

    // ------------------------------------------------------------------
    // מקורות
    // ------------------------------------------------------------------

    @Volatile
    private var cachedRules: SourceRules? = null

    var rulesText: String
        get() = preferences.getString(KEY_RULES, null) ?: SourceRules.DEFAULT_TEXT
        set(value) {
            cachedRules = null
            preferences.edit().putString(KEY_RULES, value).apply()
        }

    fun rules(): SourceRules = cachedRules ?: SourceRules.parse(rulesText).also { cachedRules = it }

    // ------------------------------------------------------------------
    // יומן זיהוי
    // ------------------------------------------------------------------

    @Synchronized
    fun appendLog(entry: DetectionEntry) {
        val updated = DetectionLog.prepend(readLog(), entry)
        preferences.edit().putString(KEY_LOG, DetectionLog.encode(updated)).apply()
    }

    fun readLog(): List<DetectionEntry> = DetectionLog.decode(preferences.getString(KEY_LOG, "") ?: "")

    fun clearLog() {
        preferences.edit().remove(KEY_LOG).apply()
    }

    // ------------------------------------------------------------------
    // שער ההסכמה לאינטרנט
    // ------------------------------------------------------------------

    /** null = עוד לא נשאל, true = אישר, false = סירב/כיבה */
    fun consent(purpose: OnlinePurpose): Boolean? =
        when (preferences.getString(purpose.prefKey, null)) {
            "yes" -> true
            "no" -> false
            else -> null
        }

    fun setConsent(purpose: OnlinePurpose, allowed: Boolean) {
        preferences.edit().putString(purpose.prefKey, if (allowed) "yes" else "no").apply()
    }

    var lastUpdateCheckMillis: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) {
            preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
        }
}
