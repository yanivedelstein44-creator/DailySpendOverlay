package com.yaniv.dailyspendoverlay.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.yaniv.dailyspendoverlay.R

/** אחרי עדכון מוצלח – הודעה קצרה עם מספר הגרסה החדשה */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val version = SelfUpdater.installedVersion(context).name
        Toast.makeText(context, context.getString(R.string.update_done_toast, version), Toast.LENGTH_LONG).show()
    }
}
