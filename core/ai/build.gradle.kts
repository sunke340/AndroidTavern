plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // 同理：ChatRequest.extraBody 暴露了 JsonElement
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
