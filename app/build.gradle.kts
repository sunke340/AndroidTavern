plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 覆盖安装必须"就地升级"，不能要求卸载重装。所以 debug 包也用**仓库里固定的**签名：
// CI 每次重新生成母版 debug keystore 会导致签名不一致（installer 直接拒装）。
// keystore/tavern-debug.jks 是公开的开发用密钥（口令写在 keystore/README.md），只用于 debug。
val debugKeystore = rootProject.file("keystore/tavern-debug.jks")

// versionCode 必须单调递增，否则覆盖安装会被判定为降级而被拒。
// CI 传 github.run_number（全局单调），本机不给就用 1。
val ciVersionCode = providers.environmentVariable("TAVERN_VERSION_CODE").orNull?.toIntOrNull()
// 让人一眼看出手机上装的是哪一次构建。
val buildId = providers.environmentVariable("TAVERN_BUILD_ID").orNull
    ?: "local-${System.currentTimeMillis() / 1000}"

android {
    namespace = "app.tavern"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tavern"
        minSdk = 26 // java.util.Base64 / PNG chunk 读写都要求 26+
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = "0.0.1-m1"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    signingConfigs {
        create("debugFixed") {
            storeFile = debugKeystore
            storePassword = "taverndebug"
            keyAlias = "tavern-debug"
            keyPassword = "taverndebug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debugFixed")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":data"))
    implementation(project(":core:card-format"))
    implementation(project(":core:prompt"))
    implementation(project(":core:ai"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    // 图标：只用 core 这一小套（ArrowBack/MoreVert/Send/Person/Settings/List 等），
    // 不引 material-icons-extended（那个包很大，编译时间也长）
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 玻璃/背景模糊（ADR 0004）。1.5.4 是最后一个依赖 Compose 1.7.3 / Kotlin 2.1.0 的版本；
    // 1.6+ 要 Compose 1.8，2.x 要 Kotlin 2.3+，升级工具链之前别动。
    // API < 31 上 blur 自动降级（haze 内部有 fallback）。
    implementation("dev.chrisbanes.haze:haze:1.5.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
