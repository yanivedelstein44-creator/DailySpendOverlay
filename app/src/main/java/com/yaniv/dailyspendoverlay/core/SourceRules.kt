package com.yaniv.dailyspendoverlay.core

/**
 * כללי מקורות: מאילו אפליקציות (ומאילו שולחי SMS) לספור הוצאות.
 *
 * פורמט – שורה לכל כלל (המשתמש עורך את זה במסך ההגדרות):
 *   com.google.android.apps.walletnfcrel   → כל ההתראות של האפליקציה
 *   com.some.bank|max                      → רק התראות שהכותרת שלהן מכילה "max"
 *   sms:max                                → הודעת SMS (מכל אפליקציית הודעות) כשהשולח הוא בדיוק "max"
 *   # הערה – מתעלמים ממנה (גם בסוף שורה)
 */
class SourceRules private constructor(
    /** package → רשימת מסנני כותרת (null = כל ההתראות) */
    private val packageRules: Map<String, List<String?>>,
    /** שמות שולחי SMS מנורמלים (התאמה מדויקת) */
    private val smsSenders: Set<String>,
) {

    fun matches(packageName: String, title: String): Boolean {
        val normalizedTitle = normalize(title)
        packageRules[packageName]?.let { filters ->
            if (filters.any { it == null || normalizedTitle.contains(it) }) return true
        }
        return packageName in SMS_APPS && normalizedTitle in smsSenders
    }

    val size: Int get() = packageRules.size + smsSenders.size

    companion object {

        /** אפליקציות ההודעות הנפוצות – רק הן נבדקות מול כללי sms: */
        val SMS_APPS: Set<String> = setOf(
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging",     // Samsung Messages
            "com.android.mms",                   // Xiaomi ועוד
            "com.android.messaging",             // AOSP
        )

        val DEFAULT_TEXT: String = """
            # ארנקים דיגיטליים
            com.google.android.apps.walletnfcrel   # Google Wallet
            com.samsung.android.spay               # Samsung Wallet
            # חברות אשראי
            com.ideomobile.leumicard               # max
            com.isracard.hatavot                   # ישראכרט
            com.onoapps.cal4u                      # כאל
            com.americanexpress.android            # אמריקן אקספרס
            # בנקים ואפליקציות תשלום
            com.leumi.leumiwallet                  # לאומי
            com.ideomobile.hapoalim                # הפועלים
            com.ideomobile.discount                # דיסקונט
            com.bnhp.payments.paymentsapp          # bit
            com.payboxapp                          # PayBox
            # SMS מחברות אשראי ובנקים (שם השולח המדויק)
            sms:max
            sms:isracard
            sms:ישראכרט
            sms:cal
            sms:כאל
            sms:leumi
            sms:לאומי
        """.trimIndent()

        private const val INVISIBLE_CHARS = "‎‏؜‪‫‬‭‮⁦⁧⁨⁩"

        fun normalize(s: String): String =
            s.filterNot { it in INVISIBLE_CHARS }.trim().lowercase()

        fun parse(text: String): SourceRules {
            val packages = LinkedHashMap<String, MutableList<String?>>()
            val senders = LinkedHashSet<String>()
            text.lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("sms:", ignoreCase = true)) {
                    val sender = normalize(line.substring(4))
                    if (sender.isNotEmpty()) senders += sender
                    return@forEach
                }
                val pkg = line.substringBefore('|').trim()
                if (pkg.isEmpty() || pkg.contains(' ')) return@forEach
                val filter = if (line.contains('|')) normalize(line.substringAfter('|')).ifEmpty { null } else null
                packages.getOrPut(pkg) { ArrayList() }.add(filter)
            }
            return SourceRules(packages, senders)
        }

        /** הכלל שצריך להוסיף כדי שהתראה כזו תיספר מעכשיו */
        fun ruleLineFor(packageName: String, title: String): String =
            if (packageName in SMS_APPS) "sms:" + title.filterNot { it in INVISIBLE_CHARS }.trim()
            else packageName

        /** מוסיף כלל לסוף הטקסט (עם הערה), אם הוא עוד לא קיים */
        fun appendRule(text: String, rule: String, comment: String?): String {
            val exists = text.lineSequence().any { it.substringBefore('#').trim().equals(rule, ignoreCase = true) }
            if (exists) return text
            val line = if (comment.isNullOrBlank()) rule else "$rule   # ${comment.trim()}"
            return if (text.isEmpty() || text.endsWith("\n")) text + line else text + "\n" + line
        }
    }
}
