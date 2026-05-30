import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/* ---------- compute version once (commits on origin/production today) ---------- */

val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
val todayDate = SimpleDateFormat("yy-MM-dd", Locale.US).format(Date())

fun gitCount(ref: String): Int? =
    runCatching {
        ByteArrayOutputStream().use { output ->
            exec {
                commandLine("git", "rev-list", "--count", "--since=$todayIso 00:00", ref)
                standardOutput = output
            }
            output.toString().trim().toIntOrNull()
        }
    }.getOrNull()

fun gitShortSuffixForRef(ref: String, shortLength: Int = 7, finalLength: Int = 3): String? =
    runCatching {
        ByteArrayOutputStream().use { output ->
            exec {
                commandLine("git", "rev-parse", "--short=$shortLength", ref)
                standardOutput = output
            }
            val shortHash = output.toString().trim()
            shortHash.takeLast(finalLength)
        }
    }.getOrNull()

val todayCommitCount =
    (gitCount("origin/production") ?: gitCount("HEAD") ?: 1).coerceAtLeast(1)

val commitSuffix =
    gitShortSuffixForRef("origin/production")
        ?: gitShortSuffixForRef("HEAD")
        ?: "000"

val versionNameComputed = "$todayDate-$todayCommitCount.$commitSuffix"

/* ---------- android ---------- */

android {
    namespace = "com.nicitaacom.androidgsm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nicitaacom.androidgsm"
        minSdk = 23
        targetSdk = 36

        versionCode = todayCommitCount + 300
        versionName = versionNameComputed

        buildConfigField("String", "VERSION_NAME", "\"$versionNameComputed\"")

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits { abi { isEnable = false } }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }

    lint { abortOnError = false; warningsAsErrors = false }

    buildFeatures { buildConfig = true }
}

/* ---------- rename APKs after assemble ---------- */

tasks.register("renameApk") {
    group = "build"
    description = "Rename produced APK(s) to gsm-v.<versionName>.apk"

    doLast {
        val apkRoot = file("${buildDir}/outputs/apk")
        if (!apkRoot.exists()) return@doLast

        val apkFiles = fileTree(apkRoot) { include("**/*.apk") }.files.sorted()
        if (apkFiles.isEmpty()) return@doLast

        apkFiles.forEach { apk ->
            val dest = apk.parentFile.resolve("gsm-v.$versionNameComputed.apk")
            if (apk.absolutePath == dest.absolutePath) return@forEach

            if (dest.exists()) dest.delete()

            val moved = apk.renameTo(dest)
            if (!moved) {
                copy {
                    from(apk)
                    into(apk.parentFile)
                    rename { dest.name }
                }
                apk.delete()
            }
        }
    }
}

tasks.matching { it.name.startsWith("assemble") }
    .configureEach { finalizedBy(tasks.named("renameApk")) }

/* ---------- deps ---------- */

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
