package com.yaniv.dailyspendoverlay.util

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.yaniv.dailyspendoverlay.listener.SpendNotificationListener

/** בדיקת ההרשאות ופתיחת מסכי ההגדרות המתאימים */
object Permissions {

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, SpendNotificationListener::class.java)

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val component = listenerComponent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component)
        }
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun openNotificationAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(activity).flattenToString())
            if (tryStart(activity, detail)) return
        }
        tryStart(activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openOverlaySettings(activity: Activity) {
        tryStart(activity, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(activity)))
    }

    /** פרטי האפליקציה – משם מאשרים "הגדרות מוגבלות" באנדרואיד 13+ */
    fun openAppDetails(activity: Activity) {
        tryStart(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity)))
    }

    /** הגדרות ההתראות של האפליקציה (אם בקשת ההרשאה כבר נדחתה והחלון לא יוצג שוב) */
    fun openAppNotificationSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        if (!tryStart(activity, intent)) openAppDetails(activity)
    }

    /** "התראות במסך מלא" (אנדרואיד 14+) – כדי שהבועה תופיע מעל מסך הנעילה */
    fun openFullScreenIntentSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            tryStart(activity, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(activity)))
        ) return
        openAppNotificationSettings(activity)
    }

    /** "התקנת אפליקציות לא ידועות" – נדרש כדי שהאפליקציה תוכל לעדכן את עצמה */
    fun openUnknownSources(activity: Activity) {
        tryStart(activity, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(activity)))
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
