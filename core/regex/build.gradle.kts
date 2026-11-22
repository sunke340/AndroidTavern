plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // 解析 ST 的脚本 JSON（公开 API 只交出本模块类型，所以用 implementation）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
