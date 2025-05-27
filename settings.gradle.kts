pluginManagement {
  includeBuild("convention-plugins")
  repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    maven("https://redirector.kotlinlang.org/maven/dev")
    gradlePluginPortal()
    mavenLocal()
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }

  versionCatalogs {
    create("kotlincrypto") {
      // https://github.com/KotlinCrypto/version-catalog/blob/master/gradle/kotlincrypto.versions.toml
      from("org.kotlincrypto:version-catalog:0.7.0")
    }
  }
}

rootProject.name = "KonTinuity"
include(":library")
