import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nicitaacom.androidgsm"
    compileSdk = 36

    defaultConfig {
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }

        applicationId = "com.nicitaacom.androidgsm"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = generateVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VERSION_NAME", "\"${generateVersionName()}\"")
    }

    splits { abi { isEnable = false } }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // v1 signature
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        abortOnError = false
        warningsAsErrors = false
    }
    buildFeatures {
        buildConfig = true
    }
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "gsm-v.${versionName}.apk"
        }
    }
}

fun generateVersionName(): String {
    val dateFormat = SimpleDateFormat("yy-MM-dd", Locale.US)
    val timeFormat = SimpleDateFormat("HHmm", Locale.US)
    val date = dateFormat.format(Date())
    val time = timeFormat.format(Date()).toInt()
    val buildNumber = (time / 100) + 1
    return "$date-$buildNumber"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.pusher)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}