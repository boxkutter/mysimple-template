rootProject.name = "simpletemplate"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Downloads a JDK automatically when the local Java has no compiler.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":shared", ":composeApp", ":androidApp")
