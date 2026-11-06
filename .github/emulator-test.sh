#!/usr/bin/env bash
# בדיקה על אמולטור אנדרואיד (רץ ב-GitHub Actions, ראה workflows/emulator-test.yml).
# שום כסף אמיתי: ה"תשלומים" הם התראות טקסט שנשלחות מה-shell של האמולטור.
# מתקין את האפליקציה, נותן הרשאות, שולח תשלומים כשהטלפון פתוח / נעול, ומצלם כל שלב.
PKG=com.yaniv.dailyspendoverlay
OUT=emulator-results
mkdir -p "$OUT"
LOG="$OUT/steps.txt"
: > "$LOG"

step() { echo "=== $*" | tee -a "$LOG"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$LOG"; }
state() {
  {
    echo "--- state: $1"
    adb shell dumpsys power | grep -E "mWakefulness=" | head -1
    adb shell dumpsys window | grep -iE "mDreamingLockscreen|mKeyguardShowing|isKeyguardShowing|KeyguardController" | head -3
    adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2
  } >> "$LOG" 2>&1
}
pay() { # tag amount merchant
  echo "pay $1 $2 $3" >> "$LOG"
  adb shell "cmd notification post -S bigtext -t 'Google Wallet' $1 '$3 ₪$2'" >> "$LOG" 2>&1
}
ui_texts() { # all texts on screen → $OUT/$1.txt
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
  python3 - "$OUT/$1.xml" > "$OUT/$1.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    for n in ET.parse(sys.argv[1]).iter('node'):
        t = (n.get('text') or n.get('content-desc') or '').strip()
        if t: print(t)
except Exception as e:
    print("ui dump failed:", e)
PY
}
tap_text() { # tap the center of the first view whose text contains $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui-tap.xml" >/dev/null 2>&1
  XY=$(python3 - "$1" "$OUT/ui-tap.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle, path = sys.argv[1], sys.argv[2]
for n in ET.parse(path).iter('node'):
    if needle in (n.get('text') or ''):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  echo "tap '$1' at [$XY]" >> "$LOG"
  [ -n "$XY" ] && adb shell input tap $XY
}
unlock() { adb shell input keyevent KEYCODE_WAKEUP; sleep 0.5; adb shell wm dismiss-keyguard; }

step "install + permissions"
adb shell getprop ro.build.version.release >> "$LOG"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >> "$LOG" 2>&1
cat > /tmp/daily_spend.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="source_rules">com.android.shell</string>
    <string name="home_currency">ILS</string>
    <string name="consent_updates">no</string>
    <string name="consent_fx">no</string>
    <boolean name="asked_notification_permission" value="true" />
</map>
XML
adb push /tmp/daily_spend.xml /data/local/tmp/daily_spend.xml >> "$LOG" 2>&1
adb shell chmod 644 /data/local/tmp/daily_spend.xml
adb shell run-as $PKG mkdir -p shared_prefs >> "$LOG" 2>&1
adb shell run-as $PKG cp /data/local/tmp/daily_spend.xml shared_prefs/daily_spend.xml >> "$LOG" 2>&1
adb shell run-as $PKG cat shared_prefs/daily_spend.xml >> "$LOG" 2>&1
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >> "$LOG" 2>&1
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow >> "$LOG" 2>&1
adb shell cmd notification allow_listener $PKG/$PKG.listener.SpendNotificationListener >> "$LOG" 2>&1
sleep 4
adb shell dumpsys notification | grep -iE "SpendNotificationListener" | head -5 >> "$LOG"

step "1. phone unlocked: payment -> bubble"
unlock; adb shell input keyevent KEYCODE_HOME; sleep 2
shot 00-home
pay p1 45.90 "Super-Pharm"
sleep 0.7; shot 01-unlocked-bubble-0.7s
sleep 1.5; shot 02-unlocked-bubble-2.2s
sleep 4; shot 03-unlocked-after-6s

step "2. phone locked (screen off): payment -> bubble over the lock screen"
adb shell input keyevent KEYCODE_SLEEP; sleep 6; state locked-before-pay
pay p2 70.10 "Shufersal"
sleep 2; shot 04-locked-bubble-2s; state locked-after-pay
sleep 5; shot 05-locked-after-7s; state locked-after-7s

step "3. unlock right after -> no repeat (seen <15s ago)"
unlock; sleep 2; shot 06-unlock-after-lock-bubble

step "4. no full-screen permission: locked payment -> notification, bubble at unlock"
adb shell appops set $PKG USE_FULL_SCREEN_INTENT deny >> "$LOG" 2>&1
adb shell input keyevent KEYCODE_SLEEP; sleep 6
pay p3 13.90 "Cafe"
sleep 1; shot 07-locked-no-fsi-screen-off; state no-fsi-after-pay
adb shell input keyevent KEYCODE_WAKEUP; sleep 0.8; shot 08-lockscreen-notification
adb shell wm dismiss-keyguard; sleep 1.5; shot 09-bubble-after-unlock
sleep 5
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow >> "$LOG" 2>&1

step "5. app: today total + log diagnostics"
adb shell am start -n $PKG/.MainActivity >> "$LOG" 2>&1; sleep 3
shot 10-app-top; ui_texts 10-app-top
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 1900 540 500 250; sleep 0.5; done
shot 11-app-bottom; ui_texts 11-app-bottom

step "6. reset + restore"
for i in 1 2 3 4 5 6 7 8 9; do adb shell input swipe 540 600 540 2000 250; sleep 0.4; done
sleep 1
tap_text "איפוס היום"; sleep 1.5; shot 12-reset-confirm
tap_text "כן"; sleep 1.5; shot 13-after-reset; ui_texts 13-after-reset
tap_text "שחזר"; sleep 1.5; shot 14-after-restore; ui_texts 14-after-restore

step "7. real lock screen with a PIN (like a real phone): payment while locked -> bubble over the lock screen"
adb shell locksettings set-pin 1111 >> "$LOG" 2>&1
adb shell input keyevent KEYCODE_SLEEP; sleep 6; state pin-locked-before-pay
pay p4 22.50 "Aroma"
sleep 2; shot 15-pin-locked-bubble-2s; state pin-locked-after-pay
sleep 5; shot 16-pin-lockscreen-after-7s; state pin-locked-after-7s
sleep 15

step "8. unlock with the PIN later -> the bubble shows again"
adb shell input keyevent KEYCODE_WAKEUP; sleep 0.7
adb shell wm dismiss-keyguard; sleep 1.2
adb shell input text 1111; adb shell input keyevent 66
sleep 2.5; shot 17-after-pin-unlock; state after-pin-unlock
adb logcat -d -s DailySpend:I >> "$LOG" 2>&1
sleep 5
adb shell locksettings clear --old 1111 >> "$LOG" 2>&1

step "9. reset done in an old version (no backup) -> restore from the log"
for i in 1 2 3 4 5 6; do adb shell input swipe 540 600 540 2000 250; sleep 0.4; done
tap_text "איפוס היום"; sleep 1.5; tap_text "כן"; sleep 2
adb shell am force-stop $PKG
adb shell run-as $PKG sed -i '/reset_undo_/d' shared_prefs/daily_spend.xml >> "$LOG" 2>&1
adb shell am start -n $PKG/.MainActivity >> "$LOG" 2>&1; sleep 3
shot 18-restore-from-log-button; ui_texts 18-restore-from-log-button
tap_text "מהיומן"; sleep 1.5; shot 19-restore-from-log-confirm; ui_texts 19-restore-from-log-confirm
tap_text "כן"; sleep 1.5; shot 20-after-restore-from-log; ui_texts 20-after-restore-from-log

step "10. listener stopped (like Samsung sleeping apps) -> payment -> listener back -> counted once"
LISTENER=$PKG/$PKG.listener.SpendNotificationListener
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell cmd notification disallow_listener $LISTENER >> "$LOG" 2>&1
sleep 2
pay p5 33.30 "Missed-While-Asleep"
sleep 3
adb shell cmd notification allow_listener $LISTENER >> "$LOG" 2>&1
sleep 5
# חיבור מחדש שני: אסור שאותו תשלום ייספר פעמיים
adb shell cmd notification disallow_listener $LISTENER >> "$LOG" 2>&1; sleep 2
adb shell cmd notification allow_listener $LISTENER >> "$LOG" 2>&1; sleep 5
adb shell am start -n $PKG/.MainActivity >> "$LOG" 2>&1; sleep 3
for i in 1 2 3 4 5 6; do adb shell input swipe 540 600 540 2000 250; sleep 0.4; done
shot 21-after-catch-up-top; ui_texts 21-after-catch-up-top
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 1900 540 500 250; sleep 0.5; done
shot 22-after-catch-up-log; ui_texts 22-after-catch-up-log

step "11. battery: status row + the system dialog"
for i in 1 2 3 4 5 6 7 8 9; do adb shell input swipe 540 600 540 2000 250; sleep 0.4; done
adb shell input swipe 540 1700 540 900 300; sleep 1
shot 23-permissions-card; ui_texts 23-permissions-card
tap_text "אשר ריצה ברקע"; sleep 2.5; shot 24-battery-dialog; ui_texts 24-battery-dialog
tap_text "Allow"; sleep 2; shot 25-after-allow; ui_texts 25-after-allow
adb shell dumpsys deviceidle whitelist | grep -i dailyspend >> "$LOG" 2>&1

step "12. experiment: process frozen (like Samsung) -> payment -> unfreeze"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am freeze --sticky $PKG >> "$LOG" 2>&1
pay p6 12.00 "While-Frozen"
sleep 4; shot 26-frozen-after-pay
adb shell am unfreeze --sticky $PKG >> "$LOG" 2>&1
sleep 0.8; shot 27-after-unfreeze-0.8s
sleep 5
adb shell am start -n $PKG/.MainActivity >> "$LOG" 2>&1; sleep 3
for i in 1 2 3 4 5 6 7 8; do adb shell input swipe 540 1900 540 500 250; sleep 0.5; done
shot 28-log-after-unfreeze; ui_texts 28-log-after-unfreeze

step "logs"
adb logcat -d -s DailySpend:V AndroidRuntime:E > "$OUT/logcat-app.txt" 2>&1
adb logcat -d > "$OUT/logcat-full.txt" 2>&1
adb shell dumpsys notification --noredact > "$OUT/dumpsys-notification.txt" 2>&1
step "done"
exit 0
