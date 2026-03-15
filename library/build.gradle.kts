@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("module.publication")
}

repositories {
  mavenCentral()
  maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
  maven("https://redirector.kotlinlang.org/maven/dev")
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    freeCompilerArgs.add("-Xexpect-actual-classes")
    freeCompilerArgs.add("-Xwhen-guards")
    freeCompilerArgs.add("-opt-in=kotlin.contracts.ExperimentalContracts")
  }
  explicitApi()
  // Matching the targets from Arrow
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_11
    }
  }
  js(IR) {
    browser()
    nodejs {
      testTask {
//        nodeJsArgs += "--prof-sampling-interval=10"
//        nodeJsArgs += "--prof"
        useMocha {
          timeout = "600s"
        }
      }
    }
  }
  wasmJs {
    browser()
    nodejs()
    d8()
  }
  // androidTarget() TODO
  // Native: https://kotlinlang.org/docs/native-target-support.html
  // -- Tier 1 --
  linuxX64()
  macosX64()
  macosArm64()
  iosSimulatorArm64()
  iosX64()
  // -- Tier 2 --
  linuxArm64()
  watchosSimulatorArm64()
  watchosX64()
  watchosArm32()
  watchosArm64()
  tvosSimulatorArm64()
  tvosX64()
  tvosArm64()
  iosArm64()
  // -- Tier 3 --
  mingwX64()
  // Android Native and watchOS not included

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.arrow.core)
        implementation(libs.arrow.fx.coroutines)
        implementation(libs.kotlinx.immutable.collections)
        api(libs.kotlinx.coroutines.core)
      }
    }
    commonTest {
      dependencies {
        implementation(libs.kotlin.test)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.kotest.assertions.core)
        implementation(libs.kotest.property)
        implementation(libs.turbine)
      }
    }

    val nonJvmMain by creating {
      dependsOn(commonMain.get())
    }
    val nonJvmTest by creating {
      dependsOn(commonTest.get())
    }

    nativeMain.get().dependsOn(nonJvmMain)
    nativeTest.get().dependsOn(nonJvmTest)
    jsMain.get().dependsOn(nonJvmMain)
    jsTest.get().dependsOn(nonJvmTest)
    wasmJsMain.get().dependsOn(nonJvmMain)
    wasmJsTest.get().dependsOn(nonJvmTest)
  }
}

tasks.withType<Test> {
  jvmArgs = listOf(
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-Xmx600m",
    // results in lots of thread name setting, which slows tests and throws off profiling, so we turn it off
    "-Dkotlinx.coroutines.debug=off",
  )
}

publishing {
  publications.withType<MavenPublication> {
    artifactId = if (name == "kotlinMultiplatform") {
      "kontinuity"
    } else {
      "kontinuity-$name"
    }
  }
}