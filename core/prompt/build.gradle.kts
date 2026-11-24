plugins {
    kotlin("jvm")
    // 公开 API 里出现了 :core:card-format 的类型，所以要用 api 而不是 implementation
    `java-library`
}

kotlin {
    // 对外 API 必须显式声明类型，避免兼容性面在重构中悄悄变大
    explicitApi()
}

dependencies {
    api(project(":core:card-format"))
    api(project(":core:lorebook"))
    api(project(":core:model"))
    // prompt_order 的类型定义在 preset 里（PromptBuilder 的公开参数用到它）
    api(project(":core:preset"))

    testImplementation(project(":core:tokenizer"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    // 夹具在仓库根的 fixtures/，从模块目录往上两跳
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
