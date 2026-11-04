# הנחיות לקלוד – DailySpendOverlay

הפרויקט: אפליקציית אנדרואיד (Kotlin, בלי AndroidX) שמציגה חלונית עם ההוצאה היומית אחרי כל תשלום. המשתמש מדבר עברית, ותיעוד והודעות בממשק כתובים בעברית.

## כללים שאסור לשבור
1. **פרטיות:** נתוני הוצאות (סכומים, יומן, כללי מקורות) לעולם לא יוצאים מהמכשיר.
   - קוד רשת קיים רק ב-`net/OnlineGateway.kt`, והוא בודק הסכמה לכל מטרה (`OnlinePurpose`).
   - אין להוסיף מטרות רשת חדשות בלי שהמשתמש ביקש במפורש.
2. **סוללה:** אין שירותי רקע, WakeLock, טיימרים או רענון מחזורי. כל עבודה מתבצעת רק בתגובה להתראה (`SpendNotificationListener`) או כשהמסך פתוח.
3. **חתימה:** תמיד `keystore/dailyspend.jks`, עם alias `dailyspend`.
   - אסור ליצור מפתח חדש, כי זה ישבור עדכונים למשתמשים קיימים.
   - `keystore/keystore.properties` לא נכנס ל-git.
4. **בלי תלויות חדשות** בזמן ריצה, אלא אם המשתמש ביקש.

## בכל גרסה חדשה
- להעלות `versionCode` ב-1 ב-`version.properties`, ולעדכן `versionName`.
- לכתוב בעברית ב-`release-notes.txt` מה חדש, משפט או שניים. זה מוצג למשתמש בחלון העדכון.
- להריץ בדיקות: `./gradlew testDebugUnitTest`. הלוגיקה הטהורה נמצאת ב-`core/` וצריכה להישאר בלי תלות באנדרואיד.
- דחיפה ל-`main` מפעילה את `.github/workflows/build-release.yml`, שבונה APK חתום ומפרסם Release. האפליקציה מוצאת אותו לבד ושואלת את המשתמש.

## מבנה
- `core/` – לוגיקה טהורה עם בדיקות יחידה:
  - `AmountParser` – Regex לסכום, מטבע ותשלומים.
  - `DailyLedger` – סכום יומי, איפוס יומי, כפילויות 60 שניות, החרגת תשלומים.
  - `Currencies`, `FxRates`, `SourceRules`, `DetectionLog`.
- `data/SpendStore` – SharedPreferences.
- `listener/` – קליטת התראות.
- `overlay/OverlayManager` – החלונית, 4 שניות, כחול/אדום.
- `update/` – עדכון עצמי דרך PackageInstaller (GitHub Releases או קובץ).
- `MainActivity` – מסך ההגדרות.
