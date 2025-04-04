pluginManagement {
    includeBuild("convention-plugins")
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
        maven("https://redirector.kotlinlang.org/maven/dev")
        gradlePluginPortal()
    }
}

rootProject.name = "KonTinuity"
include(":library")
