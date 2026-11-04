package com.yaniv.dailyspendoverlay.net

import android.content.Context
import com.yaniv.dailyspendoverlay.data.SpendStore
import com.yaniv.dailyspendoverlay.update.SelfUpdater
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** בשביל מה מותר להתחבר לאינטרנט. לכל מטרה – הסכמה נפרדת מהמשתמש. */
enum class OnlinePurpose(val prefKey: String) {
    /** בדיקה והורדה של גרסה חדשה מ-GitHub */
    UPDATES("consent_updates"),

    /** שערי מטבע יומיים מהבנק המרכזי האירופי */
    FX_RATES("consent_fx"),
}

/**
 * ===== שער ההסכמה לאינטרנט =====
 * זה הקוד היחיד באפליקציה שפותח חיבור רשת.
 *  • כל בקשה בודקת קודם שהמשתמש אישר את המטרה הספציפית – אחרת נזרקת שגיאה ולא יוצא כלום.
 *  • רק HTTPS, רק בקשות GET, בלי עוגיות ובלי מזהים.
 *  • שום נתון הוצאות לא נשלח: הבקשות לא מכילות שום מידע מהאפליקציה מלבד שם וגרסה (User-Agent).
 */
object OnlineGateway {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    class ConsentMissingException(purpose: OnlinePurpose) :
        IOException("No user consent for online access: $purpose")

    @Throws(IOException::class)
    fun getText(context: Context, purpose: OnlinePurpose, url: String, maxBytes: Int = 256 * 1024): String {
        requireConsent(context, purpose)
        val connection = open(context, url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.use { String(readLimited(it, maxBytes), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** הורדת קובץ (למשל APK של עדכון) עם דיווח התקדמות באחוזים */
    @Throws(IOException::class)
    fun download(
        context: Context,
        purpose: OnlinePurpose,
        url: String,
        target: File,
        maxBytes: Long = 100L * 1024 * 1024,
        onProgress: (Int) -> Unit = {},
    ) {
        requireConsent(context, purpose)
        val connection = open(context, url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong
            if (total > maxBytes) throw IOException("File too large")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done > maxBytes) throw IOException("File too large")
                        if (total > 0) {
                            val percent = ((done * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun requireConsent(context: Context, purpose: OnlinePurpose) {
        if (SpendStore.get(context).consent(purpose) != true) throw ConsentMissingException(purpose)
    }

    private fun open(context: Context, url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Only HTTPS is allowed" }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true // GitHub מפנה את קבצי ה-Release לשרת הקבצים שלו
        connection.useCaches = false
        connection.requestMethod = "GET"
        val version = SelfUpdater.installedVersion(context).name
        connection.setRequestProperty("User-Agent", "DailySpendOverlay/$version (Android)")
        return connection
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw IOException("Response too large")
        }
        return out.toByteArray()
    }
}
