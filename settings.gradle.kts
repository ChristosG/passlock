pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "passlock"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core-crypto", ":core-domain", ":app")
