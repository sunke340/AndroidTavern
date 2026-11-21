plugins {
    kotlin("jvm")
}

kotlin {
    // 对外 API 必须显式声明类型，避免兼容性面在重构中悄悄变大
    explicitApi()
}

dependencies {
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
