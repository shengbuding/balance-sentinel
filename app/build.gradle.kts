import java.util.Properties

// 自动版本号：CI 环境变量 BUILD_NUMBER 优先，否则用 git commit 数 + 偏移
// 需要覆盖安装时提高 VERSION_OFFSET 即可，不影响 CI
private val VERSION_OFFSET = 18

fun gitCommitCount(): Int {
    val ciBuild = System.getenv("BUILD_NUMBER")
    if (!ciBuild.isNullOrBlank()) return ciBuild.toIntOrNull() ?: 1
    return VERSION_OFFSET + (try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .start()
        process.inputStream.bufferedReader().readText().trim().toInt()
    } catch (_: Exception) { 1 })
}

fun gitVersionName(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .directory(rootProject.projectDir)
            .start()
        val result = process.inputStream.bufferedReader().readText().trim()
        // --always falls back to bare commit hash when no tags exist (e.g. shallow clone).
        // A bare hash is all-hex, 7+ chars, optionally with -dirty suffix.
        // That hash won't parse as semver, breaking UpdateChecker tests on CI.
        if (result.matches(Regex("^[0-9a-f]{7,}(-dirty)?$"))) {
            "0.0.0"
        } else {
            result
        }
    } catch (_: Exception) { "0.0.0" }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kover)
}

// Kover 覆盖率基线配置
kover {
    reports {
        filters {
            // 排除非源码类
            excludes {
                // Android generated classes
                classes("com.balancesentinel.app.BuildConfig")
                classes("com.balancesentinel.app.R*")
                // Compose preview composable functions
                annotatedBy("*Preview")
                // 数据类（自动生成方法）
                classes("com.balancesentinel.app.data.model.*_*")
            }
        }
        verify {
            rule {
                // 基线规则：仅记录不阻断，后续版本逐步提高
                bound {
                    minValue = 1  // 最低覆盖率
                    maxValue = 100
                }
            }
        }
    }
}

// Release 签名配置 — 从 keystore.properties 读取（该文件不提交到 git）
private val RELEASE_SIGNING_ERROR = "RELEASE_SIGNING_CONFIG_REQUIRED"
private val signingConfigPath = providers.gradleProperty("walletSentinel.signingConfigFile")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "keystore.properties"
private val keystorePropertiesFile = rootProject.file(signingConfigPath)
private val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
private val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
private val missingSigningKeys = signingKeys.filter {
    keystoreProperties.getProperty(it).isNullOrBlank()
}
private val configuredStoreFile = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> keystorePropertiesFile.parentFile.resolve(path).canonicalFile }
private val releaseSigningReady = keystorePropertiesFile.isFile &&
    missingSigningKeys.isEmpty() &&
    configuredStoreFile?.isFile == true
private val releaseSigningProblem = when {
    !keystorePropertiesFile.isFile -> "config file not found: ${keystorePropertiesFile.absolutePath}"
    missingSigningKeys.isNotEmpty() -> "missing fields: ${missingSigningKeys.joinToString()}"
    configuredStoreFile?.isFile != true -> "keystore not found: ${configuredStoreFile?.absolutePath}"
    else -> "unknown signing configuration error"
}
private val requestedReleaseArtifact = gradle.startParameter.taskNames.any { taskPath ->
    taskPath.substringAfterLast(':') in setOf("assembleRelease", "bundleRelease", "packageRelease")
}
if (requestedReleaseArtifact && !releaseSigningReady) {
    throw GradleException("$RELEASE_SIGNING_ERROR: $releaseSigningProblem")
}

android {
    namespace = "com.balancesentinel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.balancesentinel.app"
        minSdk = 35
        targetSdk = 35
        versionCode = gitCommitCount()
        versionName = gitVersionName()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release 签名配置（签名文件路径和密码从 keystore.properties 读取）
    // 必须在 buildTypes 之前定义，否则 signingConfigs.findByName("release") 找不到
    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = configuredStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore.properties 时使用正式签名，否则回退 debug 签名（仅用于测试）
            signingConfig = signingConfigs.findByName("release")
        }
        // Debug builds keep full debugging support; use assembleRelease for size testing
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

tasks.matching { task ->
    task.name in setOf("assembleRelease", "bundleRelease", "packageRelease")
}.configureEach {
    doFirst {
        if (!releaseSigningReady) {
            throw GradleException("$RELEASE_SIGNING_ERROR: $releaseSigningProblem")
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Network
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // JavaScript Engine (for custom usage scripts)
    implementation("org.mozilla:rhino:1.7.14")

    // Security
    implementation(libs.security.crypto)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    kapt(libs.room.compiler)

    // Test (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.room.testing)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.androidx.navigation.testing)

    // Test (Instrumented / Compose UI)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
