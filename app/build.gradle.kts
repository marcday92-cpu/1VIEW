import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/** Reads an optional properties file at the project root (local.properties). */
fun rootProperties(name: String): Properties = Properties().also { props ->
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use { props.load(it) }
}

val localProperties = rootProperties("local.properties")

android {
    namespace = "com.iptv.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iptv.tv"
        minSdk = 25
        targetSdk = 35
        versionCode = 49
        versionName = "1.1.11"
        ndk {
            abiFilters += "armeabi-v7a"
        }
        val subdlKey = localProperties.getProperty("SUBDL_API_KEY", "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "SUBDL_API_KEY", "\"$subdlKey\"")
        // Where the app looks for a newer build (see the 1VIEW releases repository).
        buildConfigField("String", "UPDATE_URL", "\"https://raw.githubusercontent.com/marcday92-cpu/1VIEW/main/version.json\"")
        // -PappIdSuffix=.rc builds a side-by-side copy (own data, own icon slot) so a release
        // candidate can be smoke-tested on a stick without uninstalling the installed app.
        (project.findProperty("appIdSuffix") as String?)?.takeIf { it.isNotBlank() }?.let { applicationIdSuffix = it }
    }

    // Private app for a handful of sticks, not a store: every build (debug and release) is signed
    // with the same key the installed app already carries, so updates install over it without
    // wiping data. signing/1view.keystore is a copy of this machine's Android debug key; keep it
    // backed up, because a stick only accepts updates signed with it.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/1view.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    // Two editions from one code base. Only the Web tab differs: the DeeTV edition ships the
    // DeeTV shortcut, the Browser edition is a plain address-bar browser. See src/deetv and
    // src/browser for the single EditionConfig object each one provides.
    // Browser is the default / GitHub identity (versionName 1.1.11). DeeTV stays a local flavour
    // with a -deetv suffix so it is never the published default.
    flavorDimensions += "edition"
    productFlavors {
        create("browser") {
            dimension = "edition"
            isDefault = true
            buildConfigField("String", "EDITION", "\"browser\"")
        }
        create("deetv") {
            dimension = "edition"
            versionNameSuffix = "-deetv"
            buildConfigField("String", "EDITION", "\"deetv\"")
        }
    }

    applicationVariants.all {
        val editionLabel = when (flavorName) {
            "deetv" -> "DeeTV"
            "browser" -> "Browser"
            else -> flavorName
        }
        outputs.map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }.forEach { output ->
            output.outputFileName = "1VIEW-$editionLabel-${buildType.name}-v$versionName.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            excludes += setOf("**/x86/**", "**/x86_64/**", "**/arm64-v8a/**")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        // lifecycle 2.8's bundled LiveData check crashes under the current Kotlin analysis API
        // ("KaCallableMemberCall ... interface was expected"), which aborts every lint run.
        // The app has no LiveData, so the check has nothing to find here.
        disable += "NullSafeMutableLiveData"
        // Compose runtime's bundled check crashes the same way; the app uses mutableIntStateOf
        // where it matters, so nothing is lost by skipping it.
        disable += "AutoboxingStateCreation"
        disable += "FrequentlyChangingValue"
        disable += "RememberInComposition"
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // HLS (.m3u8) streams: Xtream providers serve them for many live channels and the retry path
    // switches a failing .ts stream to its HLS twin. Without this module Media3 throws at setMediaItem.
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.libvlc)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.coil.compose)

    testImplementation(kotlin("test"))
}
