package com.yaniv.dailyspendoverlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.BudgetStatus
import com.yaniv.dailyspendoverlay.core.Currencies
import com.yaniv.dailyspendoverlay.core.DetectionEntry
import com.yaniv.dailyspendoverlay.core.DetectionStatus
import com.yaniv.dailyspendoverlay.core.FxRates
import com.yaniv.dailyspendoverlay.core.Money
import com.yaniv.dailyspendoverlay.core.SourceCategory
import com.yaniv.dailyspendoverlay.core.SourceRules
import com.yaniv.dailyspendoverlay.core.Themes
import com.yaniv.dailyspendoverlay.core.WorkTime
import com.yaniv.dailyspendoverlay.data.SpendStore
import com.yaniv.dailyspendoverlay.net.FxUpdater
import com.yaniv.dailyspendoverlay.net.OnlinePurpose
import com.yaniv.dailyspendoverlay.overlay.LockScreenNotifier
import com.yaniv.dailyspendoverlay.overlay.OverlayInfo
import com.yaniv.dailyspendoverlay.overlay.OverlayManager
import com.yaniv.dailyspendoverlay.update.OnlineUpdater
import com.yaniv.dailyspendoverlay.update.SelfUpdater
import com.yaniv.dailyspendoverlay.util.Permissions
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * מסך ההגדרות: הסכום של היום, הרשאות, תקציב ומטבע, מקורות, יומן זיהוי, עדכונים ושערי מטבע.
 * אין כאן שום עבודה ברקע – המסך רק מציג ומשנה הגדרות. העבודה האמיתית קורית בשירות ההתראות.
 */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val REQ_PICK_APK = 41
        private const val REQ_POST_NOTIFICATIONS = 42
        /** מתחת לשקל הבדל לא מציעים שחזור מהיומן (עיגולי המרת מטבע) */
        private const val RESTORE_FROM_LOG_MIN_DIFF_MINOR = 100L
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 30L * 60 * 1000
    }

    private lateinit var store: SpendStore
    private val updateBusy = AtomicBoolean(false)
    private val fxBusy = AtomicBoolean(false)
    private var isResumedNow = false
    private var suppressSwitchCallbacks = false
    private var consentDialogOpen = false

    private lateinit var tvVersion: TextView
    private lateinit var cardToday: View
    private lateinit var tvTodayTotal: TextView
    private lateinit var tvTodayDetails: TextView
    private lateinit var progressToday: ProgressBar
    private lateinit var tvTodayBudget: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var tvRestrictedHint: TextView
    private lateinit var btnAppInfo: Button
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var etBudget: EditText
    private lateinit var tvBudgetSymbol: TextView
    private lateinit var spHomeCurrency: Spinner
    private lateinit var swExcludeInstallments: Switch
    private lateinit var swatchRow: LinearLayout
    private lateinit var tvBrightness: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var swOverlayFull: Switch
    private lateinit var swCashBills: Switch
    private lateinit var swStayUntilSwipe: Switch
    private lateinit var swOnUnlock: Switch
    private lateinit var swLogUnlisted: Switch
    private lateinit var sourceSwitches: LinearLayout
    private lateinit var swLockScreen: Switch
    private lateinit var tvLockStatus: TextView
    private lateinit var btnLockGrant: Button
    private lateinit var etRules: EditText
    private lateinit var etWage: EditText
    private lateinit var tvWageSymbol: TextView
    private lateinit var etMonthlyWage: EditText
    private lateinit var tvMonthlySymbol: TextView
    private lateinit var tvMonthlyWageInfo: TextView
    private lateinit var etWorkHours: EditText
    private lateinit var sourcesHeader: View
    private lateinit var sourcesBody: View
    private lateinit var tvSourcesArrow: TextView
    private lateinit var tvSourcesSummary: TextView
    private lateinit var tvLogEmpty: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var tvInstalledVersion: TextView
    private lateinit var swOnlineUpdates: Switch
    private lateinit var swFxRates: Switch
    private lateinit var tvUpdateStatus: TextView
    private lateinit var tvFxStatus: TextView
    private lateinit var btnCheckNow: Button
    private lateinit var btnAllowInstall: Button

    // ==================================================================
    // מחזור חיים
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        store = SpendStore.get(this)
        bindViews()
        applyInsets()
        setupToday()
        setupPermissions()
        setupBudgetAndCurrency()
        setupAppearance()
        setupRules()
        setupLog()
        setupOnline()
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        store.preferences.registerOnSharedPreferenceChangeListener(this)
        refreshAll()

        // פעולות אונליין – רק אם המשתמש אישר, ורק כשהמסך פתוח (אין עבודה ברקע)
        refreshFx(force = false)
        if (OnlineUpdater.isConfigured && store.consent(OnlinePurpose.UPDATES) == true &&
            System.currentTimeMillis() - store.lastUpdateCheckMillis > AUTO_UPDATE_CHECK_INTERVAL_MS
        ) {
            checkForUpdate(manual = false)
        }
        maybeAskConsentOnce()
        maybeAskNotificationPermission()
    }

    override fun onPause() {
        isResumedNow = false
        store.preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /** השירות רשם עסקה בזמן שהמסך פתוח → מרעננים (נקרא על ה-main thread) */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshToday()
        refreshLog()
    }

    private fun refreshAll() {
        refreshToday()
        refreshPermissions()
        refreshBudgetAndCurrency()
        refreshAppearance()
        refreshLog()
        refreshOnline()
    }

    // ==================================================================
    // תצוגה מקצה לקצה (Android 15+) – ריפוד לפי פסי המערכת והמקלדת
    // ==================================================================

    @Suppress("DEPRECATION")
    private fun enableEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun applyInsets() {
        val content = findViewById<View>(R.id.content)
        val base = content.paddingTop
        findViewById<View>(R.id.rootScroll).setOnApplyWindowInsetsListener { _, insets ->
            val (left, top, right, bottom) = systemInsets(insets)
            content.setPadding(base + left, base + top, base + right, base + bottom)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun systemInsets(insets: WindowInsets): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            listOf(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }

    private fun bindViews() {
        tvVersion = findViewById(R.id.tvVersion)
        cardToday = findViewById(R.id.cardToday)
        tvTodayTotal = findViewById(R.id.tvTodayTotal)
        tvTodayDetails = findViewById(R.id.tvTodayDetails)
        progressToday = findViewById(R.id.progressToday)
        tvTodayBudget = findViewById(R.id.tvTodayBudget)
        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        tvRestrictedHint = findViewById(R.id.tvRestrictedHint)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBattery = findViewById(R.id.btnBattery)
        etBudget = findViewById(R.id.etBudget)
        tvBudgetSymbol = findViewById(R.id.tvBudgetSymbol)
        spHomeCurrency = findViewById(R.id.spHomeCurrency)
        swExcludeInstallments = findViewById(R.id.swExcludeInstallments)
        swatchRow = findViewById(R.id.swatchRow)
        tvBrightness = findViewById(R.id.tvBrightness)
        seekBrightness = findViewById(R.id.seekBrightness)
        swOverlayFull = findViewById(R.id.swOverlayFull)
        swCashBills = findViewById(R.id.swCashBills)
        swStayUntilSwipe = findViewById(R.id.swStayUntilSwipe)
        swOnUnlock = findViewById(R.id.swOnUnlock)
        swLogUnlisted = findViewById(R.id.swLogUnlisted)
        sourceSwitches = findViewById(R.id.sourceSwitches)
        swLockScreen = findViewById(R.id.swLockScreen)
        tvLockStatus = findViewById(R.id.tvLockStatus)
        btnLockGrant = findViewById(R.id.btnLockGrant)
        etRules = findViewById(R.id.etRules)
        etWage = findViewById(R.id.etWage)
        tvWageSymbol = findViewById(R.id.tvWageSymbol)
        etMonthlyWage = findViewById(R.id.etMonthlyWage)
        tvMonthlySymbol = findViewById(R.id.tvMonthlySymbol)
        tvMonthlyWageInfo = findViewById(R.id.tvMonthlyWageInfo)
        etWorkHours = findViewById(R.id.etWorkHours)
        sourcesHeader = findViewById(R.id.sourcesHeader)
        sourcesBody = findViewById(R.id.sourcesBody)
        buildSourceSwitches()
        tvSourcesArrow = findViewById(R.id.tvSourcesArrow)
        tvSourcesSummary = findViewById(R.id.tvSourcesSummary)
        tvLogEmpty = findViewById(R.id.tvLogEmpty)
        logContainer = findViewById(R.id.logContainer)
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        swOnlineUpdates = findViewById(R.id.swOnlineUpdates)
        swFxRates = findViewById(R.id.swFxRates)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        tvFxStatus = findViewById(R.id.tvFxStatus)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnAllowInstall = findViewById(R.id.btnAllowInstall)
    }

    // ==================================================================
    // היום
    // ==================================================================

    private fun setupToday() {
        findViewById<Button>(R.id.btnPreviewOk).setOnClickListener { preview(over = false) }
        findViewById<Button>(R.id.btnPreviewOver).setOnClickListener { preview(over = true) }
        findViewById<Button>(R.id.btnResetToday).setOnClickListener {
            confirm(getString(R.string.reset_confirm)) {
                store.resetToday()
                refreshToday()
            }
        }
        findViewById<Button>(R.id.btnRestoreToday).setOnClickListener {
            if (store.restorableToday() != null) {
                if (store.restoreToday()) toast(getString(R.string.restored_today))
                refreshToday()
                return@setOnClickListener
            }
            // אין גיבוי (איפוס בגרסה ישנה): חישוב מחדש מהיומן, ורק אחרי אישור
            val rebuilt = store.rebuildTodayFromLog(getString(R.string.log_installments_excluded))
                ?: return@setOnClickListener
            val home = store.homeCurrency
            val current = store.todaySnapshot()
            confirm(
                getString(
                    R.string.restore_from_log_confirm,
                    Money.format(rebuilt.totalMinor, home),
                    rebuilt.count,
                    Money.format(current.totalMinor, home),
                )
            ) {
                store.applyRebuiltToday(rebuilt)
                toast(getString(R.string.restored_today))
                refreshToday()
            }
        }
    }

    private fun refreshToday() {
        val today = store.todaySnapshot()
        val home = store.homeCurrency
        val status = BudgetStatus(today.totalMinor, store.budgetMinor)
        tvTodayTotal.text = Money.format(today.totalMinor, home)
        val last = today.lastTx
        tvTodayDetails.text = when {
            today.count == 0 || last == null -> getString(R.string.today_none)
            today.count == 1 -> getString(R.string.today_details_one, Money.format(last))
            else -> getString(R.string.today_details_many, today.count, Money.format(last))
        }
        OverlayManager.applyProgress(this, progressToday, status)
        progressToday.visibility = if (status.hasBudget) View.VISIBLE else View.GONE
        tvTodayBudget.text = if (status.hasBudget) {
            OverlayManager.budgetLine(this, status, home)
        } else {
            getString(R.string.today_no_budget)
        }
        cardToday.background = OverlayManager.cardBackground(this, status.isOver, cornerDp = 18f)

        // "שחזר": אחרי איפוס (מהגיבוי), או – אם אין גיבוי – כשהסכום מהיומן שונה ממה שנספר עכשיו.
        // הכפתור תמיד מוצג; כשאין מה לשחזר הוא אפור, כדי שלא ייראה כאילו נעלם.
        val restore = findViewById<Button>(R.id.btnRestoreToday)
        restore.visibility = View.VISIBLE
        restore.isEnabled = true
        restore.alpha = 1f
        val backup = store.restorableToday()
        val fromLog = if (backup != null) null
        else store.rebuildTodayFromLog(getString(R.string.log_installments_excluded))
            ?.takeIf { kotlin.math.abs(it.totalMinor - today.totalMinor) >= RESTORE_FROM_LOG_MIN_DIFF_MINOR }
        when {
            backup != null -> {
                restore.text = getString(R.string.btn_restore_today, Money.format(backup.totalMinor, home))
            }
            fromLog != null -> {
                // היומן מראה פחות ממה שנספר = כפילויות שנספרו פעמיים → "תקן"; יותר = "שחזר"
                val fix = fromLog.totalMinor < today.totalMinor
                restore.text = getString(
                    if (fix) R.string.btn_fix_from_log else R.string.btn_restore_from_log,
                    Money.format(fromLog.totalMinor, home),
                )
            }
            else -> {
                restore.text = getString(R.string.btn_restore_none)
                restore.isEnabled = false
                restore.alpha = 0.45f
            }
        }
    }

    /** תצוגה מקדימה של החלונית – לא נשמרת ולא משנה את הסכום */
    private fun preview(over: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.need_overlay_first))
            Permissions.openOverlaySettings(this)
            return
        }
        val home = store.homeCurrency
        val total = store.todaySnapshot().totalMinor
        // בלי תקציב מוגדר: "תצוגת חריגה" משתמשת בתקציב לדוגמה, רק לתצוגה
        val budget = if (store.budgetMinor > 0) store.budgetMinor else if (over) total + 30_00L else 0L
        val sampleMinor = if (over) maxOf(budget - total + 62_00L, 45_90L) else 45_90L
        val newTotal = total + sampleMinor
        OverlayManager.show(
            this,
            OverlayInfo(
                lastTx = Amount(sampleMinor, home),
                lastTxHomeMinor = null,
                totalMinor = newTotal,
                budgetMinor = budget,
                homeCurrency = home,
                justCrossed = budget > 0 && total <= budget && newTotal > budget,
                isPreview = true,
                workText = WorkTime.describe(sampleMinor, store.hourlyWageMinor),
                previousTotalMinor = total,
            )
        )
    }

    // ==================================================================
    // הרשאות
    // ==================================================================

    private fun setupPermissions() {
        btnNotifAccess.setOnClickListener { Permissions.openNotificationAccess(this) }
        btnOverlay.setOnClickListener { Permissions.openOverlaySettings(this) }
        btnAppInfo.setOnClickListener { Permissions.openAppDetails(this) }
        btnBattery.setOnClickListener { Permissions.requestBatteryUnrestricted(this) }
    }

    private fun refreshPermissions() {
        val notif = Permissions.isNotificationListenerEnabled(this)
        tvNotifStatus.setText(if (notif) R.string.perm_notif_ok else R.string.perm_notif_missing)
        tvNotifStatus.setTextColor(getColor(if (notif) R.color.ok_green else R.color.warn_red))
        btnNotifAccess.visibility = if (notif) View.GONE else View.VISIBLE
        val restricted = !notif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        tvRestrictedHint.visibility = if (restricted) View.VISIBLE else View.GONE
        btnAppInfo.visibility = if (restricted) View.VISIBLE else View.GONE

        val overlay = Settings.canDrawOverlays(this)
        tvOverlayStatus.setText(if (overlay) R.string.perm_overlay_ok else R.string.perm_overlay_missing)
        tvOverlayStatus.setTextColor(getColor(if (overlay) R.color.ok_green else R.color.warn_red))
        btnOverlay.visibility = if (overlay) View.GONE else View.VISIBLE

        // חיסכון בסוללה: אם הוא מגביל, הטלפון מקפיא את האפליקציה והבועה קופצת רק כשפותחים אותה
        val battery = Permissions.isBatteryUnrestricted(this)
        tvBatteryStatus.setText(if (battery) R.string.perm_battery_ok else R.string.perm_battery_missing)
        tvBatteryStatus.setTextColor(getColor(if (battery) R.color.ok_green else R.color.warn_red))
        btnBattery.visibility = if (battery) View.GONE else View.VISIBLE
    }

    // ==================================================================
    // תקציב, מטבע ראשי, עסקאות בתשלומים
    // ==================================================================

    private fun setupBudgetAndCurrency() {
        findViewById<Button>(R.id.btnSaveBudget).setOnClickListener {
            val raw = etBudget.text.toString()
            val value = if (raw.isBlank()) 0L else Money.parseToMinor(raw)
            if (value == null) {
                etBudget.error = getString(R.string.budget_invalid)
                return@setOnClickListener
            }
            store.budgetMinor = value // 0 = בלי תקציב
            hideKeyboard()
            toast(getString(if (value > 0) R.string.saved else R.string.saved_no_budget))
            refreshToday()
        }

        val items = Currencies.ALL.map { getString(R.string.currency_item, it.symbol, it.nameHe, it.code) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spHomeCurrency.adapter = adapter
        selectCurrentCurrency()
        spHomeCurrency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = Currencies.ALL[position].code
                if (code == store.homeCurrency) return
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.currency_change_title, code))
                    .setMessage(R.string.currency_change_msg)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        store.changeHomeCurrency(code)
                        refreshBudgetAndCurrency()
                        refreshToday()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> selectCurrentCurrency() }
                    .setOnCancelListener { selectCurrentCurrency() }
                    .show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.btnSaveWage).setOnClickListener {
            val raw = etWage.text.toString()
            val value = if (raw.isBlank()) 0L else Money.parseToMinor(raw)
            if (value == null) {
                etWage.error = getString(R.string.budget_invalid)
                return@setOnClickListener
            }
            store.hourlyWageMinor = value // 0 = כבוי
            store.monthlyWageMinor = 0L
            etMonthlyWage.setText("")
            refreshBudgetAndCurrency()
            hideKeyboard()
            toast(getString(if (value > 0) R.string.saved else R.string.wage_saved_off))
        }

        findViewById<Button>(R.id.btnSaveMonthlyWage).setOnClickListener {
            val raw = etMonthlyWage.text.toString()
            val value = if (raw.isBlank()) 0L else Money.parseToMinor(raw)
            if (value == null) {
                etMonthlyWage.error = getString(R.string.budget_invalid)
                return@setOnClickListener
            }
            val hoursRaw = etWorkHours.text.toString().trim()
            val hours = if (hoursRaw.isBlank()) WorkTime.MONTHLY_HOURS else hoursRaw.toLongOrNull()
            if (hours == null || hours !in 1L..744L) {
                etWorkHours.error = getString(R.string.budget_invalid)
                return@setOnClickListener
            }
            store.workHoursPerMonth = hours
            store.monthlyWageMinor = value
            store.hourlyWageMinor = WorkTime.hourlyFromMonthly(value, hours)
            etWage.setText("")
            etWage.clearFocus()
            etMonthlyWage.clearFocus()
            refreshBudgetAndCurrency()
            hideKeyboard()
            toast(getString(if (value > 0) R.string.saved else R.string.wage_saved_off))
        }

        swExcludeInstallments.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.excludeInstallments = checked
        }
    }

    private fun selectCurrentCurrency() {
        val index = Currencies.ALL.indexOfFirst { it.code == store.homeCurrency }.coerceAtLeast(0)
        spHomeCurrency.setSelection(index, false)
    }

    private fun refreshBudgetAndCurrency() {
        val home = Currencies.get(store.homeCurrency)
        if (!etBudget.hasFocus()) {
            etBudget.setText(if (store.budgetMinor > 0) Money.formatNumber(store.budgetMinor).replace(",", "") else "")
        }
        tvBudgetSymbol.text = home.symbol
        tvWageSymbol.text = home.symbol
        if (!etWage.hasFocus()) {
            etWage.setText(if (store.hourlyWageMinor > 0) Money.formatNumber(store.hourlyWageMinor).replace(",", "") else "")
        }
        tvMonthlySymbol.text = home.symbol
        if (!etWorkHours.hasFocus()) {
            etWorkHours.setText(if (store.workHoursPerMonth != WorkTime.MONTHLY_HOURS) store.workHoursPerMonth.toString() else "")
        }
        val monthly = store.monthlyWageMinor
        if (!etMonthlyWage.hasFocus()) {
            etMonthlyWage.setText(if (monthly > 0) Money.formatNumber(monthly).replace(",", "") else "")
        }
        if (monthly > 0 && store.hourlyWageMinor > 0) {
            tvMonthlyWageInfo.text = getString(
                R.string.wage_monthly_info,
                Money.format(store.hourlyWageMinor, store.homeCurrency),
                store.workHoursPerMonth.toInt()
            )
            tvMonthlyWageInfo.visibility = View.VISIBLE
        } else {
            tvMonthlyWageInfo.visibility = View.GONE
        }
        selectCurrentCurrency()
        suppressSwitchCallbacks = true
        swExcludeInstallments.isChecked = store.excludeInstallments
        suppressSwitchCallbacks = false
    }

    // ==================================================================
    // מראה החלונית: צבע + בהירות באחוזים
    // ==================================================================

    private fun setupAppearance() {
        swCashBills.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.showCashBreakdown = checked
        }
        swStayUntilSwipe.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.stayUntilSwipe = checked
        }
        swOverlayFull.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.overlayFull = checked
        }
        swLockScreen.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            store.showOnLockScreen = checked
            if (checked) maybeAskNotificationPermission() // מבקש פעם אחת; אחר כך הכפתור שמתחת
            refreshLockScreen()
        }
        btnLockGrant.setOnClickListener {
            if (!LockScreenNotifier.canPost(this)) requestNotificationAccess()
            else Permissions.openFullScreenIntentSettings(this)
        }
        swLogUnlisted.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.logUnlisted = checked
        }
        swOnUnlock.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchCallbacks) store.showOnUnlock = checked
        }
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBrightness.text = getString(R.string.appearance_brightness, progress)
                if (!fromUser) return
                store.brightnessPercent = progress
                refreshToday()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshAppearance() {
        seekBrightness.progress = store.brightnessPercent
        tvBrightness.text = getString(R.string.appearance_brightness, store.brightnessPercent)
        suppressSwitchCallbacks = true
        swOverlayFull.isChecked = store.overlayFull
        swCashBills.isChecked = store.showCashBreakdown
        swStayUntilSwipe.isChecked = store.stayUntilSwipe
        swOnUnlock.isChecked = store.showOnUnlock
        swLogUnlisted.isChecked = store.logUnlisted
        swLockScreen.isChecked = store.showOnLockScreen
        suppressSwitchCallbacks = false
        refreshLockScreen()
        buildSwatches()
    }

    // ==================================================================
    // התראה במסך הנעילה: הרשאת התראות (אנדרואיד 13+)
    // ==================================================================

    /** מציג הודעה + כפתור רק כשהמתג דלוק וחסר אישור: קודם התראות, אחר כך "מסך מלא" */
    private fun refreshLockScreen() {
        val message = when {
            !store.showOnLockScreen -> null
            !LockScreenNotifier.canPost(this) -> R.string.lock_perm_missing
            !LockScreenNotifier.canUseFullScreen(this) -> R.string.lock_fsi_missing
            else -> null
        }
        tvLockStatus.setTextColor(getColor(R.color.warn_red))
        if (message != null) tvLockStatus.setText(message)
        tvLockStatus.visibility = if (message != null) View.VISIBLE else View.GONE
        btnLockGrant.visibility = if (message != null) View.VISIBLE else View.GONE
    }

    /** בקשה אוטומטית אחת בלבד (בפתיחת האפליקציה או כשמדליקים את המתג); אחר כך רק הכפתור */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!store.showOnLockScreen || store.askedNotificationPermission || consentDialogOpen) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        store.askedNotificationPermission = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
    }

    /** הכפתור: חלון הבקשה של המערכת, ואם הוא כבר לא יוצג (נדחה פעמיים) – הגדרות ההתראות של האפליקציה */
    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val dialogWontShow = store.askedNotificationPermission &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (!dialogWontShow) {
                store.askedNotificationPermission = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
                return
            }
        }
        Permissions.openAppNotificationSettings(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) refreshLockScreen()
    }

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        val selected = store.themeColor
        val size = (44 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        val ring = (3 * resources.displayMetrics.density).toInt()
        for (theme in Themes.ALL) {
            val swatch = View(this)
            val isSelected = theme.id == selected.id
            swatch.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(theme.start, theme.end)
            ).apply {
                shape = GradientDrawable.OVAL
                if (isSelected) setStroke(ring, Color.WHITE) else setStroke(ring / 3, getColor(R.color.card_stroke))
            }
            swatch.contentDescription =
                if (isSelected) getString(R.string.appearance_color_selected, theme.nameHe) else theme.nameHe
            swatch.setOnClickListener {
                store.themeColor = theme
                buildSwatches()
                refreshToday()
            }
            swatchRow.addView(swatch, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
        }
    }

    // ==================================================================
    // מקורות
    // ==================================================================

    private fun setupRules() {
        etRules.setText(store.rulesText)
        // הרשימה ארוכה – סגורה כברירת מחדל, נפתחת בלחיצה על הכותרת
        sourcesHeader.setOnClickListener { setSourcesOpen(sourcesBody.visibility != View.VISIBLE) }
        setSourcesOpen(false)
        findViewById<Button>(R.id.btnSaveRules).setOnClickListener {
            store.rulesText = etRules.text.toString()
            hideKeyboard()
            toast(getString(R.string.saved))
            refreshSourcesSummary()
            refreshLog()
        }
        findViewById<Button>(R.id.btnDefaultRules).setOnClickListener {
            confirm(getString(R.string.defaults_confirm)) {
                store.rulesText = SourceRules.DEFAULT_TEXT
                etRules.setText(store.rulesText)
                refreshSourcesSummary()
                refreshLog()
            }
        }
    }

    // ==================================================================
    // יומן זיהוי
    // ==================================================================

    private fun setupLog() {
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            store.clearLog()
            refreshLog()
        }
    }

    private fun refreshLog() {
        logContainer.removeAllViews()
        val entries = store.readLog()
        tvLogEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val rules = store.rules()
        val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        for (entry in entries) {
            val row = layoutInflater.inflate(R.layout.item_detection, logContainer, false)
            row.findViewById<TextView>(R.id.tvLogTitle).text =
                listOf(entry.appLabel, Money.format(entry.amount)).joinToString(" · ")
            val time = Instant.ofEpochMilli(entry.timeMillis).atZone(ZoneId.systemDefault()).format(formatter)
            row.findViewById<TextView>(R.id.tvLogSub).text =
                listOf(time, statusText(entry), entry.title).filter { it.isNotBlank() }.joinToString(" · ")
            val add = row.findViewById<Button>(R.id.btnLogAdd)
            val canAdd = entry.status == DetectionStatus.NOT_LISTED && !rules.matches(entry.packageName, entry.title)
            add.visibility = if (canAdd) View.VISIBLE else View.GONE
            add.setOnClickListener { addRuleFor(entry) }
            logContainer.addView(row)
        }
    }

    private fun statusText(entry: DetectionEntry): String = when (entry.status) {
        DetectionStatus.COUNTED ->
            getString(R.string.log_counted) + if (entry.detail.isNotEmpty()) " (${entry.detail})" else ""
        DetectionStatus.DUPLICATE -> getString(R.string.log_duplicate, entry.detail)
        DetectionStatus.EXCLUDED -> getString(R.string.log_excluded, entry.detail)
        DetectionStatus.NOT_LISTED -> getString(R.string.log_not_listed)
        DetectionStatus.NO_RATE -> getString(R.string.log_no_rate, entry.detail)
    }

    private fun addRuleFor(entry: DetectionEntry) {
        val line = SourceRules.ruleLineFor(entry.packageName, entry.title)
        store.rulesText = SourceRules.appendRule(store.rulesText, line, entry.appLabel)
        etRules.setText(store.rulesText)
        toast(getString(R.string.rule_added, line))
        refreshSourcesSummary()
        refreshLog()
    }

    private fun setSourcesOpen(open: Boolean) {
        sourcesBody.visibility = if (open) View.VISIBLE else View.GONE
        tvSourcesArrow.text = if (open) "▴" else "▾"
        if (!open) hideKeyboard()
        refreshSourcesSummary()
    }

    private fun sourceLabel(c: SourceCategory): Int = when (c) {
        SourceCategory.GOOGLE_WALLET -> R.string.src_google_wallet
        SourceCategory.SAMSUNG_WALLET -> R.string.src_samsung_wallet
        SourceCategory.BIT -> R.string.src_bit
        SourceCategory.PAYBOX -> R.string.src_paybox
        SourceCategory.CREDIT_CARDS -> R.string.src_credit_cards
        SourceCategory.BANKS -> R.string.src_banks
        SourceCategory.SMS -> R.string.src_sms
    }

    /** מתג לכל סוג מקור (נבנה פעם אחת) */
    private fun buildSourceSwitches() {
        sourceSwitches.removeAllViews()
        for (category in SourceCategory.values()) {
            val sw = Switch(this)
            sw.text = getString(sourceLabel(category))
            sw.setTextColor(getColor(R.color.text_primary))
            sw.textSize = 15f
            sw.minimumHeight = (44 * resources.displayMetrics.density).toInt()
            sw.isChecked = store.isSourceEnabled(category)
            sw.setOnCheckedChangeListener { _, checked ->
                store.setSourceEnabled(category, checked)
                refreshSourcesSummary()
            }
            sourceSwitches.addView(
                sw,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun refreshSourcesSummary() {
        val open = sourcesBody.visibility == View.VISIBLE
        val on = SourceCategory.values().filter { store.isSourceEnabled(it) }.joinToString(", ") { getString(sourceLabel(it)) }
        tvSourcesSummary.text = when {
            open -> getString(R.string.sources_summary_open)
            on.isEmpty() -> getString(R.string.sources_summary_none)
            else -> getString(R.string.sources_summary_active, on)
        }
    }

    // ==================================================================
    // אינטרנט באישור: עדכוני גרסה + שערי מטבע
    // ==================================================================

    private fun setupOnline() {
        swOnlineUpdates.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            store.setConsent(OnlinePurpose.UPDATES, checked)
            if (checked) checkForUpdate(manual = true)
        }
        swFxRates.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitchCallbacks) return@setOnCheckedChangeListener
            store.setConsent(OnlinePurpose.FX_RATES, checked)
            if (checked) refreshFx(force = true)
        }
        btnCheckNow.setOnClickListener {
            if (store.consent(OnlinePurpose.UPDATES) == true) {
                checkForUpdate(manual = true)
            } else {
                askConsent { checkForUpdate(manual = true) }
            }
        }
        findViewById<Button>(R.id.btnPickApk).setOnClickListener { pickApk() }
        btnAllowInstall.setOnClickListener { Permissions.openUnknownSources(this) }
    }

    private fun refreshOnline() {
        val version = SelfUpdater.installedVersion(this)
        val versionText = getString(R.string.version_fmt, version.name, version.code)
        tvVersion.text = versionText
        tvInstalledVersion.text = versionText

        suppressSwitchCallbacks = true
        swOnlineUpdates.isEnabled = OnlineUpdater.isConfigured
        swOnlineUpdates.isChecked = OnlineUpdater.isConfigured && store.consent(OnlinePurpose.UPDATES) == true
        swFxRates.isChecked = store.consent(OnlinePurpose.FX_RATES) == true
        suppressSwitchCallbacks = false

        btnCheckNow.isEnabled = OnlineUpdater.isConfigured
        btnCheckNow.alpha = if (OnlineUpdater.isConfigured) 1f else 0.4f
        if (!OnlineUpdater.isConfigured && tvUpdateStatus.visibility != View.VISIBLE) {
            showUpdateStatus(getString(R.string.update_online_not_configured))
        }
        btnAllowInstall.visibility = if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
        refreshFxStatus()
    }

    private fun refreshFxStatus() {
        val online = store.onlineFxTable()
        tvFxStatus.text = if (online != null) {
            getString(R.string.fx_status_online, online.date)
        } else {
            getString(R.string.fx_status_builtin, FxRates.BUILT_IN.date)
        }
    }

    /** חלון ההסכמה – פעם אחת, בפתיחה הראשונה */
    private fun maybeAskConsentOnce() {
        val fxUnknown = store.consent(OnlinePurpose.FX_RATES) == null
        val updatesUnknown = OnlineUpdater.isConfigured && store.consent(OnlinePurpose.UPDATES) == null
        if (fxUnknown || updatesUnknown) askConsent(null)
    }

    private fun askConsent(onAllowed: (() -> Unit)?) {
        if (consentDialogOpen) return
        consentDialogOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.update_consent_title)
            .setMessage(R.string.update_consent_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.update_consent_allow) { _, _ ->
                consentDialogOpen = false
                store.setConsent(OnlinePurpose.UPDATES, true)
                store.setConsent(OnlinePurpose.FX_RATES, true)
                refreshOnline()
                refreshFx(force = true)
                if (onAllowed != null) onAllowed() else checkForUpdate(manual = false)
            }
            .setNegativeButton(R.string.update_consent_deny) { _, _ ->
                consentDialogOpen = false
                if (store.consent(OnlinePurpose.UPDATES) == null) store.setConsent(OnlinePurpose.UPDATES, false)
                if (store.consent(OnlinePurpose.FX_RATES) == null) store.setConsent(OnlinePurpose.FX_RATES, false)
                refreshOnline()
            }
            .show()
    }

    private fun refreshFx(force: Boolean) {
        if (store.consent(OnlinePurpose.FX_RATES) != true) return
        if (!force && !FxUpdater.isStale(store)) return
        if (!fxBusy.compareAndSet(false, true)) return
        val app = applicationContext
        Thread {
            val error = try {
                FxUpdater.refresh(app)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                fxBusy.set(false)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshFxStatus()
                if (error != null && force) tvFxStatus.text = getString(R.string.fx_refresh_failed, error)
            }
        }.start()
    }

    private fun checkForUpdate(manual: Boolean) {
        if (!OnlineUpdater.isConfigured || store.consent(OnlinePurpose.UPDATES) != true) return
        if (!updateBusy.compareAndSet(false, true)) return
        if (manual) showUpdateStatus(getString(R.string.update_checking))
        val app = applicationContext
        Thread {
            val result = runCatching { OnlineUpdater.fetchLatest(app) }
            store.lastUpdateCheckMillis = System.currentTimeMillis()
            updateBusy.set(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { remote ->
                    val current = SelfUpdater.installedVersion(this)
                    if (remote.versionCode > current.code) {
                        showUpdateStatus(null)
                        promptOnlineUpdate(remote, current)
                    } else {
                        showUpdateStatus(getString(R.string.update_up_to_date, nowHm()))
                    }
                }.onFailure { e ->
                    if (manual) {
                        showUpdateStatus(getString(R.string.update_check_failed, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
        }.start()
    }

    private fun promptOnlineUpdate(remote: OnlineUpdater.Remote, current: SelfUpdater.Version) {
        if (!isResumedNow) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, remote.versionName))
            .setMessage(
                getString(
                    R.string.update_available_msg,
                    current.name, current.code, remote.versionName, remote.versionCode,
                    remote.notes.ifBlank { "—" }
                )
            )
            .setPositiveButton(R.string.btn_install) { _, _ -> downloadAndInstall(remote) }
            .setNegativeButton(R.string.btn_later, null)
            .show()
    }

    private fun downloadAndInstall(remote: OnlineUpdater.Remote) {
        if (!ensureInstallPermission()) return
        if (!updateBusy.compareAndSet(false, true)) return
        showUpdateStatus(getString(R.string.update_downloading, 0))
        val app = applicationContext
        Thread {
            try {
                val file = OnlineUpdater.download(app, remote) { percent ->
                    runOnUiThread { showUpdateStatus(getString(R.string.update_downloading, percent)) }
                }
                runOnUiThread { showUpdateStatus(getString(R.string.update_verifying)) }
                when (val check = SelfUpdater.inspect(app, file)) {
                    is SelfUpdater.Check.Ready -> {
                        runOnUiThread { showUpdateStatus(getString(R.string.update_installing)) }
                        SelfUpdater.install(app, check.file)
                    }
                    is SelfUpdater.Check.Problem -> runOnUiThread { showUpdateStatus(check.message) }
                }
            } catch (e: OnlineUpdater.HashMismatchException) {
                runOnUiThread { showUpdateStatus(getString(R.string.update_err_hash)) }
            } catch (e: Exception) {
                runOnUiThread {
                    showUpdateStatus(getString(R.string.update_err_generic, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                updateBusy.set(false)
            }
        }.start()
    }

    // ---- עדכון מקובץ (גיבוי בלי אינטרנט) ----

    private fun pickApk() {
        if (!ensureInstallPermission()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(SelfUpdater.APK_MIME, "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, REQ_PICK_APK)
        } catch (e: ActivityNotFoundException) {
            showUpdateStatus(getString(R.string.update_err_read))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (!updateBusy.compareAndSet(false, true)) return
        showUpdateStatus(getString(R.string.update_verifying))
        val app = applicationContext
        Thread {
            val check = try {
                SelfUpdater.inspect(app, SelfUpdater.copyFromUri(app, uri))
            } catch (e: Exception) {
                SelfUpdater.Check.Problem(getString(R.string.update_err_read))
            } finally {
                updateBusy.set(false)
            }
            runOnUiThread { if (!isDestroyed) onLocalApkChecked(check) }
        }.start()
    }

    private fun onLocalApkChecked(check: SelfUpdater.Check) {
        when (check) {
            is SelfUpdater.Check.Problem -> showUpdateStatus(check.message)
            is SelfUpdater.Check.Ready -> {
                showUpdateStatus(null)
                val message = if (check.sameVersion) {
                    getString(R.string.update_same_version, check.current.name)
                } else {
                    getString(
                        R.string.update_dialog_msg,
                        check.current.name, check.current.code, check.incoming.name, check.incoming.code
                    )
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_dialog_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.btn_install) { _, _ -> installInBackground(check.file) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun installInBackground(file: File) {
        showUpdateStatus(getString(R.string.update_installing))
        val app = applicationContext
        Thread {
            try {
                SelfUpdater.install(app, file)
            } catch (e: Exception) {
                runOnUiThread { showUpdateStatus(getString(R.string.update_err_generic, e.message ?: "")) }
            }
        }.start()
    }

    private fun ensureInstallPermission(): Boolean {
        if (packageManager.canRequestPackageInstalls()) return true
        AlertDialog.Builder(this)
            .setMessage(R.string.install_permission_needed)
            .setPositiveButton(R.string.btn_allow_install) { _, _ -> Permissions.openUnknownSources(this) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }

    // ==================================================================
    // עזרים
    // ==================================================================

    private fun showUpdateStatus(text: String?) {
        tvUpdateStatus.text = text.orEmpty()
        tvUpdateStatus.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hideKeyboard() {
        val focused = currentFocus ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun nowHm(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
}
