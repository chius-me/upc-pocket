import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val rawVersionCode = (providers.gradleProperty("appVersionCode").orNull
    ?: providers.environmentVariable("APP_VERSION_CODE").orNull)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val resolvedVersionCode = rawVersionCode?.let { raw ->
    val parsed = raw.toIntOrNull()
    require(parsed != null && parsed in 1..999_999_999) {
        "Invalid appVersionCode '$raw': expected integer in 1..999999999"
    }
    parsed
} ?: 2

val rawVersionName = (providers.gradleProperty("appVersionName").orNull
    ?: providers.environmentVariable("APP_VERSION_NAME").orNull)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val resolvedVersionName = rawVersionName?.also { name ->
    require(name.matches(Regex("""^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$"""))) {
        "Invalid appVersionName '$name': expected SemVer"
    }
} ?: "0.2.0"

val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use { load(it) }
    }
}

fun signingProp(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey)
        ?: providers.gradleProperty(propKey).orNull
        ?: providers.environmentVariable(envKey).orNull)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingProp("storeFile", "ANDROID_KEYSTORE_PATH")
val releaseStoreFile = releaseStorePath?.let { path ->
    val candidate = file(path)
    if (candidate.isFile) candidate else rootProject.file(path).takeIf { it.isFile }
}
val releaseStorePassword = signingProp("storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "ANDROID_KEY_PASSWORD")

val hasCompleteReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
if (requireReleaseSigning) {
    require(hasCompleteReleaseSigning) {
        "Release signing required (-PrequireReleaseSigning=true), but keystore file/credentials are missing or invalid."
    }
}

android {
    namespace = "me.chius.upcpocket"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.chius.upcpocket"
        minSdk = 31
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
