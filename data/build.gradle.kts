plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.tavern.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // api：CardStore 的公开签名里出现 CharacterCard / CardRef 之外的 core 类型时，
    // 上层（app/feature）必须能编译到它们。
    api(project(":core:model"))
    api(project(":core:card-format"))

    // 暂时没有额外依赖：SAF 读流、SharedPreferences、File 都是平台的。
    // 加 Room/DataStore 时再在这里加，别提前引入。
}
