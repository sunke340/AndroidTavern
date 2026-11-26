plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

// 零第三方依赖：这里只有相位仲裁与决策表，纯 Kotlin + 原子引用。
dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
