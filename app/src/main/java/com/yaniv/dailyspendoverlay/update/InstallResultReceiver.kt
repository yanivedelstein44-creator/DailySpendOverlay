package com.yaniv.dailyspendoverlay.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.yaniv.dailyspendoverlay.R

/** מקבל מהמערכת את תוצאת ההתקנה: צריך אישור משתמש / הצליח / נכשל */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.yaniv.dailyspendoverlay.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // המערכת מבקשת אישור (בפעם הראשונה, או באנדרואיד 11 ומטה) – פותחים את חלון האישור שלה
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: RuntimeException) {
                        toast(context, context.getString(R.string.update_err_generic, e.message ?: ""))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> toast(context, context.getString(R.string.update_success))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                toast(context, context.getString(R.string.update_failed, status, message))
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
