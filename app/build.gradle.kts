import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI overrides: -PetknVersionCode=<monotonic> -PetknVersionName=<x.y.z>
val baseVersionCode = 113
val baseVersionName = "1.18.0"
val versionCodeOverride = providers.gradleProperty("etknVersionCode").orNull?.toIntOrNull()
val versionNameOverride = providers.gradleProperty("etknVersionName").orNull

// Release signing comes from the environment only (GitHub Actions secrets). Never commit keys.
val signingStore = providers.environmentVariable("ETKN_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("ETKN_KEYSTORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("ETKN_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ETKN_KEY_PASSWORD").orNull
val hasSigning = listOf(signingStore, signingStorePassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "xyz.etkn.mobile"
    compileSdk = 37
    // AGP 9 creates unit-test tasks only for testBuildType; test the variant that ships.
    testBuildType = "release"

    defaultConfig {
        applicationId = "xyz.etkn.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: baseVersionCode
        versionName = versionNameOverride ?: baseVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingStore!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
        // Media3 @UnstableApi is opted in module-wide via -opt-in; lint does not see compiler opt-ins.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "MissingTranslation", "UnsafeOptInUsageError")
    }

    // Library strings in other languages are never shown.
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            // Build metadata only; third-party notices for shipped native code stay in assets/.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/*.version",
                "META-INF/androidx/**", "**/*.kotlin_builtins", "DebugProbesKt.bin",
            )
        }
        jniLibs {
            // Compressed in the APK (extracted at install): libmpv's FFmpeg / libplacebo would
            // otherwise triple the download.
            useLegacyPackaging = true
            // No 32-bit x86 build is shipped; mpv's x86 libraries would only bloat the universal APK.
            excludes += "**/x86/*.so"
            // mpv-android and NextLib both ship FFmpeg 9 (symbol version LIBAVCODEC_63), libass and libc++.
            // mpv's copies are full builds and win; NextLib's bridge links against the same
            // sonames. CI checks the packaged libavcodec is mpv's.
            // Per shipped ABI only: a pick-first rule would otherwise outrank the x86 exclude.
            pickFirsts += listOf("arm64-v8a", "armeabi-v7a", "x86_64").flatMap { abi ->
                listOf("libavcodec", "libavutil", "libswresample", "libswscale", "libass", "libc++_shared").map { "**/$abi/$it.so" }
            }
        }
    }
}

// Each ABI APK gets a distinct, monotonically increasing versionCode: base * 10 + abi slot.
val abiSlots = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val slot = abiSlots[abi] ?: 0
            output.versionCode.set((versionCodeOverride ?: baseVersionCode) * 10 + slot)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

composeCompiler {
    includeSourceInformation.set(false)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.haze)
    implementation(libs.material.color.utilities)

    // Declared before Media3 / NextLib so its native libraries come first when merged.
    implementation(libs.mpv.android)
    implementation(libs.media3.exoplayer)
    // 用户口径（2026-11-23）：预加载 + 磁盘缓存需要 Media3 的 cache 数据源
    // （SimpleCache / CacheDataSource 都在 media3-datasource 里，没有独立的 -cache 包）。
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.nextlib.media3ext)

    testImplementation(libs.junit)
}
