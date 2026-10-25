plugins {
    // Provisions JDK 25 through the foojay Disco API when no local JDK 25 is found.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "agents-room-runtime"
