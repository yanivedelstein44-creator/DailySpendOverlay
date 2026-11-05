package com.yaniv.dailyspendoverlay.overlay

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/** מצב המכשיר ברגע התשלום */
object ScreenState {

    /**
     * חלונית צפה נראית רק כשהמסך דלוק והטלפון פתוח. כשהוא נעול (או המסך כבוי) אנדרואיד מסתיר אותה,
     * ובנוסף ה"טיימר" שמסיר אותה לא רץ כשהמכשיר ישן – היא נשארה "תקועה" ופתאום קפצה כשפתחת את הנעילה.
     */
    fun canShowOverlay(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        return interactive && !locked
    }
}
