rootProject.name = "passlock"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":core-crypto", ":core-domain")
