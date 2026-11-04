import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// גרסה: מוגדרת בקובץ version.properties שבשורש הפרויקט.
// בכל גרסה חדשה מעלים את versionCode ב-1 (חובה!) ומעדכנים את versionName.
// ---------------------------------------------------------------------------
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// ---------------------------------------------------------------------------
// מפתח חתימה קבוע: keystore/dailyspend.jks
// הסיסמה: מקובץ keystore/keystore.properties (במחשב שלך, לא עולה ל-GitHub)
//          או ממשתנה הסביבה KEYSTORE_PASSWORD (ב-GitHub Actions, מתוך Secret).
// אותו מפתח לכל הבניות – כך כל APK חדש מתעדכן מעל הקיים בלי למחוק נתונים.
// ---------------------------------------------------------------------------
val signingProps = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystorePassword: String? =
    signingProps.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")

// הריפו ב-GitHub שממנו האפליקציה בודקת עדכונים. GitHub Actions ממלא אותו אוטומטית.
val updateRepo: String = providers.gradleProperty("updateRepo").orElse("").get()

android {
    namespace = "com.yaniv.dailyspendoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaniv.dailyspendoverlay"
        minSdk = 26          // Android 8.0 – נדרש בשביל TYPE_APPLICATION_OVERLAY
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").trim().toInt()
        versionName = versionProps.getProperty("versionName").trim()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/dailyspend.jks")
            storePassword = keystorePassword
            keyAlias = signingProps.getProperty("keyAlias") ?: "dailyspend"
            keyPassword = signingProps.getProperty("keyPassword") ?: keystorePassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        textReport = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// הודעה ברורה אם חסרה סיסמת המפתח (במקום שגיאת חתימה סתומה)
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    doFirst {
        if (keystorePassword.isNullOrBlank()) {
            throw GradleException(
                "חסרה סיסמת מפתח החתימה: צור keystore/keystore.properties (ראה README) " +
                    "או הגדר את משתנה הסביבה KEYSTORE_PASSWORD."
            )
        }
    }
}

dependencies {
    // אין תלויות בזמן ריצה מלבד Kotlin – אין ספריות צד שלישי, אין SDK-ים של מעקב.
    testImplementation("junit:junit:4.13.2")
}
