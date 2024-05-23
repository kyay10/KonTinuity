import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidLibrary)
  alias(libs.plugins.jetbrainsCompose)
  alias(libs.plugins.compose.compiler)
  id("module.publication")
}

composeCompiler {
  includeSourceInformation = true
  enableStrongSkippingMode = true
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
    optIn.addAll("kotlinx.cinterop.BetaInteropApi", "kotlinx.cinterop.ExperimentalForeignApi")
  }
  explicitApi()
  applyDefaultHierarchyTemplate()
  jvm()
  androidTarget {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_1_8
    }
    publishLibraryVariants("release")
  }
  js {
    browser()
  }
  iosX64()
  iosArm64()
  iosSimulatorArm64()
  linuxArm64()
  linuxX64()

  macosArm64()
  macosX64()
  mingwX64()

  tvosArm64()
  tvosSimulatorArm64()
  tvosX64()

  watchosArm32()
  watchosArm64()
  watchosSimulatorArm64()
  watchosX64()

  sourceSets {
    val commonMain by getting {
      repositories {
        google()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
      }
      dependencies {
        implementation(libs.arrow.core)
        implementation(libs.arrow.fx.coroutines)
        api(compose.runtime)
        api(libs.kotlinx.coroutines.core)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.kotest.assertions.core)
        implementation(libs.kotest.property)
        implementation(libs.turbine)
      }
    }

    // We use a common folder instead of a common source set because there is no commonizer
    // which exposes the browser APIs across these two targets.
    jsMain {
      kotlin.srcDir("src/browserMain/kotlin")
    }
    wasmJsMain {
      kotlin.srcDir("src/browserMain/kotlin")
    }

    val darwinMain by creating {
      dependsOn(commonMain)
    }

    val displayLinkMain by creating {
      dependsOn(darwinMain)
    }
    val displayLinkTest by creating {
      dependsOn(commonTest)
    }

    val quartzCoreMain by creating {
      dependsOn(displayLinkMain)
    }

    iosMain {
      dependsOn(quartzCoreMain)
    }
    iosTest {
      // TODO Link against XCTest in order to get frame pulses on iOS.
      // dependsOn(displayLinkTest)
    }

    tvosMain {
      dependsOn(quartzCoreMain)
    }
    tvosTest {
      // TODO Link against XCTest in order to get frame pulses on tvOS.
      // dependsOn(displayLinkTest)
    }

    macosMain {
      dependsOn(displayLinkMain)
    }
    macosTest {
      dependsOn(displayLinkTest)
    }

    watchosMain {
      dependsOn(darwinMain)
    }
  }
}

android {
  namespace = "org.jetbrains.kotlinx.multiplatform.library.template"
  compileSdk = libs.versions.android.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.android.minSdk.get().toInt()
  }
}
