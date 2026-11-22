rootProject.name = "androidtavern"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // 单模块阶段：项目级仓库声明放在这里，模块里不要各自写 repositories
    repositories {
        mavenCentral()
        google()
    }
}

// 只有纯 JVM 模块会进这里，直到 M0 建 Android 工程。
// 好处：本机（无 Android SDK、1.7 GiB 内存）能直接跑 :core:* 的单测。
include(":core:model")
include(":core:card-format")
include(":core:lorebook")
include(":core:tokenizer")
include(":core:regex")
include(":core:prompt")
include(":core:ai")
include(":core:chat-format")
include(":data")
include(":app")
