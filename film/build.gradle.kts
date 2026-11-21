plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Compile for Java 17 with whichever JDK runs Gradle (17 or newer) rather than requiring an
// exact JDK 17 install: F-Droid's build server only has Java 21, and toolchain download is off.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("contact.in", System.getProperty("contact.in") ?: "")
    systemProperty("contact.out", System.getProperty("contact.out") ?: "")
    systemProperty("contact.only", System.getProperty("contact.only") ?: "")
    systemProperty("contact.chunk", System.getProperty("contact.chunk") ?: "")
    systemProperty("assets.in", System.getProperty("assets.in") ?: "")
    systemProperty("assets.out", System.getProperty("assets.out") ?: "")
    maxHeapSize = "3g"
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
