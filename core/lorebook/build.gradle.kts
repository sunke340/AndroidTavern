plugins {
    kotlin("jvm")
    // 纯逻辑模块：对外 API 只有本模块自己的类型，所以不需要 java-library 的 api 传播
}

kotlin {
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
