import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tapcreator.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tapcreator.app"
        // minSdk 26（Android 8.0）：代码广泛使用 API 26+（Base64/nio/NotificationChannel/
        // startForegroundService/Process.isAlive），升到 26 可删掉全部 NewApi 分支；
        // 7.x 设备 2026 年占比 <2%，不再值得用版本分支+desugar 维护。
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // 供 MigrationTestHelper 读取导出的 Room schema JSON
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    // signingConfigs 必须先于 buildTypes 声明：release 里 getByName("release") 是立即求值，
    // 写在后面会导致 SigningConfig with name 'release' not found（本地无 signing.properties
    // 时因 ?.let 短路从未暴露，CI 写密钥后才炸）。
    signingConfigs {
        create("release") {
            signingPropertiesFile()?.let { props ->
                storeFile = File(props.getProperty("keystoreFile", ""))
                storePassword = props.getProperty("keystorePassword", "")
                keyAlias = props.getProperty("keyAlias", "")
                keyPassword = props.getProperty("keyPassword", "")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 读取本地 signing.properties（已 gitignore）。含 keystore 且文件存在时才签名，
            // 否则 release 退回未签名，避免占位/缺失配置导致构建失败。
            signingPropertiesFile()?.let { props ->
                val storePath = props.getProperty("keystoreFile")
                if (storePath != null && File(storePath).exists()) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
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
        // AppModule 按 BuildConfig.DEBUG 决定是否挂 OkHttp 日志拦截器，需显式开启（AGP8 默认关闭）
        buildConfig = true
    }
    // Room schema 导出目录：exportSchema=true 时由 KSP 写入此目录，JSON 快照入 git 便于迁移 diff
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Crashlytics 运行时 SDK：无 google-services.json 时静默 no-op 不崩溃。
// 若需上传崩溃映射（release 混淆），请补 google-services 插件 + google-services.json，
// 并重新在根/模块 build 中声明 com.google.firebase.crashlytics Gradle 插件。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)

    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    // Media3 替代已归档的 ExoPlayer2（包名 androidx.media3.*）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.firebase.crashlytics)

    debugImplementation(libs.androidx.ui.tooling)
}

/** 读取 signing.properties（可缺省）：优先模块级 app/，回退工程根；都不存在返回 null，未配置则 release 不签名 */
fun signingPropertiesFile(): Properties? {
    val file = listOf(File("$projectDir/signing.properties"), File("signing.properties")).firstOrNull { it.exists() }
        ?: return null
    return Properties().apply { file.inputStream().use { load(it) } }
}