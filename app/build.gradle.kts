import java.util.Properties

// 自动版本号：CI 环境变量 BUILD_NUMBER 优先，否则用 git commit 数 + 偏移
// 需要覆盖安装时提高 VERSION_OFFSET 即可，不影响 CI
private val VERSION_OFFSET = 10

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
        process.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "1.0.0" }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
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
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreConfig = keystorePropertiesFile.exists()
if (hasKeystoreConfig) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
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
        if (hasKeystoreConfig) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

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

    // Security
    implementation(libs.security.crypto)

    // Test (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Test (Instrumented / Compose UI)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
