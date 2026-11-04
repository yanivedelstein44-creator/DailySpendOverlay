package com.yaniv.dailyspendoverlay.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.yaniv.dailyspendoverlay.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * התקנת עדכון של האפליקציה עצמה מקובץ APK (שהורד מ-GitHub או נבחר מהטלפון).
 *
 * לפני התקנה בודקים: שזו באמת DailySpendOverlay, שהגרסה לא ישנה יותר,
 * ושהקובץ חתום באותו מפתח כמו הגרסה המותקנת (אחרת אנדרואיד ממילא יסרב).
 * ההתקנה דרך PackageInstaller – באנדרואיד 12+ אפילו בלי חלון אישור נוסף.
 */
object SelfUpdater {

    const val APK_MIME = "application/vnd.android.package-archive"

    data class Version(val name: String, val code: Long)

    sealed interface Check {
        data class Ready(val file: File, val current: Version, val incoming: Version) : Check {
            val sameVersion: Boolean get() = incoming.code == current.code
        }

        data class Problem(val message: String) : Check
    }

    fun installedVersion(context: Context): Version {
        val info = packageInfo(context, context.packageName, 0)
        return Version(info?.versionName ?: "?", info?.let { versionCode(it) } ?: 0L)
    }

    /** מעתיק קובץ שנבחר בבורר הקבצים לתיקיית המטמון של האפליקציה */
    @Throws(IOException::class)
    fun copyFromUri(context: Context, uri: Uri): File {
        val file = freshUpdateFile(context)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    /** קובץ ריק ונקי בתיקיית המטמון (מוחק עדכונים ישנים) */
    fun freshUpdateFile(context: Context): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "update.apk")
    }

    fun inspect(context: Context, file: File): Check {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val archive = archiveInfo(pm, file.path, flags)
            ?: return Check.Problem(context.getString(R.string.update_err_not_apk))

        if (archive.packageName != context.packageName) {
            return Check.Problem(context.getString(R.string.update_err_other_app, archive.packageName))
        }
        val current = installedVersion(context)
        val incoming = Version(archive.versionName ?: "?", versionCode(archive))
        if (incoming.code < current.code) {
            return Check.Problem(context.getString(R.string.update_err_older, incoming.name, current.name))
        }
        if (signaturesMatch(context, archive) == false) {
            return Check.Problem(context.getString(R.string.update_err_signature))
        }
        return Check.Ready(file, current, incoming)
    }

    /** מתקין דרך PackageInstaller. לקרוא מ-thread רקע (יש כאן כתיבה לדיסק). */
    @Throws(IOException::class)
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // אנדרואיד 12+: אפליקציה שמעדכנת את עצמה לא צריכה אישור נוסף (ראה UPDATE_PACKAGES_WITHOUT_USER_ACTION)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            context.packageManager.getPackageInfo(pkg, flags)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, flags)
        }

    /** true/false אם אפשר לבדוק; null אם המערכת לא מסרה חתימות (ואז אנדרואיד יבדוק בעצמו בהתקנה) */
    @Suppress("DEPRECATION")
    private fun signaturesMatch(context: Context, archive: PackageInfo): Boolean? {
        val flags = PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val installed = packageInfo(context, context.packageName, flags) ?: return null
        val incoming = signerSet(archive) ?: return null
        val current = signerSet(installed) ?: return null
        return incoming == current
    }

    @Suppress("DEPRECATION")
    private fun signerSet(info: PackageInfo): Set<String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }?.let { signers ->
                return signers.map { it.toCharsString() }.toSet()
            }
        }
        return info.signatures?.takeIf { it.isNotEmpty() }?.map { it.toCharsString() }?.toSet()
    }
}
