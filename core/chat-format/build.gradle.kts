plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // api：ChatFile.metadata / ChatMessage.raw 是公开 API，上层要能看见 JsonObject
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    api(project(":core:model"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":core:card-format"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
