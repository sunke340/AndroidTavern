plugins {
    kotlin("jvm")
    // 公开 API 里不会出现 JsonObject，但解析结果里的 Map/List 都是标准库类型，
    // 所以 kotlinx-serialization 只用 implementation。
    `java-library`
}

kotlin {
    explicitApi()
}

dependencies {
    // api：`EffectiveConfig` 的解析入参里出现 `ConfigOverride`（:core:model），
    // 上层（app/data）只依赖 core:preset 时也要能编译到它。
    api(project(":core:model"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    // 测试要读仓库根的 vendor/ 与 fixtures/，从模块目录往上三跳
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
