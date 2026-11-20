plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

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
