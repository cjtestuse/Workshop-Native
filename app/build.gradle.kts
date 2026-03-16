plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.ksp)
}

fun configuredString(name: String): String? {
    val fromGradle = (findProperty(name) as String?)?.trim()
    if (!fromGradle.isNullOrEmpty()) return fromGradle
    return System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
}

fun configuredInt(name: String, defaultValue: Int): Int =
    configuredString(name)?.toIntOrNull() ?: defaultValue

val appVersionCode = configuredInt("APP_VERSION_CODE", 1)
val appVersionName = configuredString("APP_VERSION_NAME") ?: "1.0.0"

val releaseStoreFilePath = configuredString("RELEASE_STORE_FILE")
val releaseStorePassword = configuredString("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configuredString("RELEASE_KEY_ALIAS")
val releaseKeyPassword = configuredString("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.slay.workshopnative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slay.workshopnative"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"cjtestuse\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"Workshop-Native\"")
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStoreFilePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.google.hilt.android)
    implementation(libs.google.material)
    implementation(libs.javaSteam)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    debugImplementation(libs.compose.ui.tooling)

    ksp(libs.androidx.room.compiler)
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.google.hilt.compiler)
}
