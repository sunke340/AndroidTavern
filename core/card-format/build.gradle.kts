plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // api 而非 implementation：CharacterCard.characterBook 是公开 API 的一部分，
    // 使用者需要 JsonObject 这个类型。用 implementation 会让 App 编译期
    // 报 "Cannot access class JsonObject"。
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // 卡内嵌世界书的映射函数把 LorebookEntry 交出去，也是公开 API 的一部分
    api(project(":core:lorebook"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
