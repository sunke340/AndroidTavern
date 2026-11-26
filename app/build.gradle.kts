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
    // 聊天记录导出/导入：原版 .jsonl 的读写（raw JSON 是真相，别在 app 里手拼）
    implementation(project(":core:chat-format"))
    implementation(project(":core:prompt"))
    implementation(project(":core:lorebook"))
    implementation(project(":core:tokenizer"))
    implementation(project(":core:regex"))
    implementation(project(":core:ai"))
    // 一轮生成的提交权与收尾决策（纯 JVM，本机可单测；P0）
    implementation(project(":core:generation"))

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
    // 拖动排序：只做"长按拖动换位"这一件事（LazyColumn + 边缘自动滚 + placement 动画）。
    // 版本钉 3.1.0：它声明的 org.jetbrains.compose.* 仍是 1.7.0（对上我们的 BOM 2024.12.01
    // = Compose 1.7.6）。**升级前先看它依赖的 Compose 版本**，升到要 1.8 的版本就得连工具链一起升。
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
