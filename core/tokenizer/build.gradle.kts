plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    // jtokkit：OpenAI 那套 BPE 编码的纯 JVM 实现（cl100k_base / o200k_base），
    // 与原版前端用的 gpt-tokenizer 出自同一套词表，所以计数能对上夹具。
    implementation("com.knuddels:jtokkit:1.1.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
