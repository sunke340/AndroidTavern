plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test { useJUnitPlatform() }
