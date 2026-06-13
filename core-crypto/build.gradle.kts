plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }
