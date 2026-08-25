import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val releaseStorePath = providers.environmentVariable("VOLUME_MAPPER_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("VOLUME_MAPPER_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("VOLUME_MAPPER_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("VOLUME_MAPPER_KEY_PASSWORD").orNull
val releasePrivacyPolicyUrl = providers.environmentVariable("VOLUME_MAPPER_PRIVACY_POLICY_URL").orNull
val releaseSigningInputs = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningInput = releaseSigningInputs.any { !it.isNullOrBlank() }
val hasReleaseSigning = releaseSigningInputs.all { !it.isNullOrBlank() }
if (hasAnyReleaseSigningInput && !hasReleaseSigning) {
    throw GradleException(
        "Release 签名环境变量必须完整提供 keystore、store password、alias 和 key password；不会静默回退到未签名产物。",
    )
}

fun requireHttpsPrivacyPolicyUrl(value: String?): String {
    val candidate = value?.trim().orEmpty()
    val uri = runCatching { URI(candidate) }.getOrNull()
    if (
        uri == null ||
        !uri.isAbsolute ||
        !uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.isNullOrBlank() ||
        !uri.userInfo.isNullOrEmpty()
    ) {
        throw GradleException(
            "已签名 Release 必须通过 VOLUME_MAPPER_PRIVACY_POLICY_URL 提供不含用户凭据的有效 HTTPS URL。",
        )
    }
    return candidate
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.spcdts.volumemapper"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.spcdts.volumemapper"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"\"")
        manifestPlaceholders["privacyPolicyUrl"] = ""
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStorePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                val privacyPolicyUrl = requireHttpsPrivacyPolicyUrl(releasePrivacyPolicyUrl)
                signingConfig = signingConfigs.getByName("release")
                buildConfigField(
                    "String",
                    "PRIVACY_POLICY_URL",
                    privacyPolicyUrl.asBuildConfigString(),
                )
                manifestPlaceholders["privacyPolicyUrl"] = privacyPolicyUrl
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

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "zh-rCN")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
