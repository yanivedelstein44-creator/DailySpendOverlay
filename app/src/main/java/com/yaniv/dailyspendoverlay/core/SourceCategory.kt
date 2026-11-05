package com.yaniv.dailyspendoverlay.core

/**
 * סוגי מקורות עם מתג בהגדרות. כל התראה ממקור מוכר שייך לסוג אחד; סוג כבוי = ההתראות שלו לא נספרות.
 * מקור שהמשתמש הוסיף בעצמו לרשימה (שלא מופיע כאן) תמיד נספר.
 * ברירת מחדל: רק Google Wallet, bit ו-PayBox דלוקים.
 */
enum class SourceCategory(val defaultOn: Boolean) {
    GOOGLE_WALLET(true),
    SAMSUNG_WALLET(false),
    BIT(true),
    PAYBOX(true),
    CREDIT_CARDS(false),
    BANKS(false),
    SMS(false);

    companion object {
        private val BY_PACKAGE: Map<String, SourceCategory> = mapOf(
            "com.google.android.apps.walletnfcrel" to GOOGLE_WALLET,
            "com.samsung.android.spay" to SAMSUNG_WALLET,
            "com.bnhp.payments.paymentsapp" to BIT,
            "com.payboxapp" to PAYBOX,
            "com.ideomobile.leumicard" to CREDIT_CARDS,   // Max
            "com.isracard.hatavot" to CREDIT_CARDS,
            "com.onoapps.cal4u" to CREDIT_CARDS,
            "com.americanexpress.android" to CREDIT_CARDS,
            "com.leumi.leumiwallet" to BANKS,
            "com.ideomobile.hapoalim" to BANKS,
            "com.ideomobile.discount" to BANKS,
        )

        /** שולחי ה-SMS של ברירת המחדל (חברות אשראי ובנקים). שולח ש-המשתמש הוסיף בעצמו לא נחשב כאן */
        private val KNOWN_SMS_SENDERS = setOf("max", "isracard", "ישראכרט", "cal", "כאל", "leumi", "לאומי")

        /** הסוג של ההתראה, או null אם זה מקור שהמשתמש הוסיף בעצמו */
        fun of(packageName: String, title: String): SourceCategory? =
            BY_PACKAGE[packageName]
                ?: if (packageName in SourceRules.SMS_APPS && SourceRules.normalize(title) in KNOWN_SMS_SENDERS) SMS else null

        /** כבוי/דלוק לפי מה שהמשתמש בחר (ברירת מחדל כשלא בחר) */
        fun isCounted(category: SourceCategory?, enabled: (SourceCategory) -> Boolean): Boolean =
            category == null || enabled(category)
    }
}
