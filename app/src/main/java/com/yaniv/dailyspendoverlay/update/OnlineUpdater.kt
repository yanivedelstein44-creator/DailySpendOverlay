package com.yaniv.dailyspendoverlay.update

import android.content.Context
import com.yaniv.dailyspendoverlay.BuildConfig
import com.yaniv.dailyspendoverlay.net.OnlineGateway
import com.yaniv.dailyspendoverlay.net.OnlinePurpose
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * בדיקת עדכונים ב-GitHub Releases של הריפו שבנה את ה-APK (BuildConfig.UPDATE_REPO).
 * כל Release מכיל version.json + DailySpendOverlay.apk (נוצרים אוטומטית ע"י GitHub Actions).
 * הורדה רק אחרי הסכמה (OnlineGateway), ותמיד עם אימות SHA-256 + חתימה לפני התקנה.
 */
object OnlineUpdater {

    data class Remote(
        val versionCode: Long,
        val versionName: String,
        val tag: String,
        val apkName: String,
        val sha256: String,
        val notes: String,
    )

    class HashMismatchException : IOException("SHA-256 mismatch")

    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    /** האם ה-APK הזה נבנה ב-GitHub (ולכן יודע מאיפה לבדוק עדכונים) */
    val isConfigured: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    @Throws(IOException::class)
    fun fetchLatest(context: Context): Remote {
        val url = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/version.json"
        val json = try {
            JSONObject(OnlineGateway.getText(context, OnlinePurpose.UPDATES, url))
        } catch (e: org.json.JSONException) {
            throw IOException("Bad version.json", e)
        }
        val remote = Remote(
            versionCode = json.optLong("versionCode", -1L),
            versionName = json.optString("versionName", "?"),
            tag = json.optString("tag", ""),
            apkName = json.optString("apk", "DailySpendOverlay.apk"),
            sha256 = json.optString("sha256", ""),
            notes = json.optString("notes", ""),
        )
        if (remote.versionCode <= 0 || !SAFE_NAME.matches(remote.tag) || !SAFE_NAME.matches(remote.apkName)) {
            throw IOException("Bad version.json")
        }
        return remote
    }

    @Throws(IOException::class)
    fun download(context: Context, remote: Remote, onProgress: (Int) -> Unit): File {
        val file = SelfUpdater.freshUpdateFile(context)
        val url = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/${remote.tag}/${remote.apkName}"
        OnlineGateway.download(context, OnlinePurpose.UPDATES, url, file, onProgress = onProgress)
        if (remote.sha256.isNotEmpty() && !SelfUpdater.sha256(file).equals(remote.sha256, ignoreCase = true)) {
            file.delete()
            throw HashMismatchException()
        }
        return file
    }
}
