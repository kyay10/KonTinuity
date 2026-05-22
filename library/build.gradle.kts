@file:OptIn(ExperimentalWasmDsl::class)

import kotlinx.benchmark.gradle.BenchmarkConfiguration
import kotlinx.benchmark.gradle.JsBenchmarkTarget
import kotlinx.benchmark.gradle.JsBenchmarksExecutor
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.kotlinxBenchmark)
  alias(libs.plugins.spotless)
  id("module.publication")
}

repositories {
  mavenCentral()
  maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
  maven("https://redirector.kotlinlang.org/maven/dev")
  mavenLocal()
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xcontext-parameters",
      "-opt-in=kotlin.contracts.ExperimentalContracts",
      "-Xwarning-level=DSL_MARKER_APPLIED_TO_WRONG_TARGET:disabled",
      "-Xreturn-value-checker=full",
      "-Xcontext-sensitive-resolution",
    )
  }
  explicitApi()
  // Matching the targets from Arrow
  jvm()
  jvmToolchain(25)
  js {
    browser()
    nodejs {
      testTask {
        // nodeJsArgs += "--prof-sampling-interval=10"
        // nodeJsArgs += "--prof"
        useMocha { timeout = "600s" }
      }
    }
  }
  wasmJs {
    browser()
    nodejs()
  }
  // androidTarget() TODO
  // Native: https://kotlinlang.org/docs/native-target-support.html
  // -- Tier 1 --
  linuxX64()
  macosArm64()
  iosSimulatorArm64()
  // -- Tier 2 --
  linuxArm64()
  watchosSimulatorArm64()
  watchosArm32()
  watchosArm64()
  tvosSimulatorArm64()
  tvosArm64()
  iosArm64()
  // -- Tier 3 --
  mingwX64()
  // Android Native and watchOS not included

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.kotlinx.benchmark.runtime)
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
        implementation(kotlincrypto.hash.md)
      }
    }
  }
}

spotless {
  kotlin {
    target("**/*.kt")
    ktfmt(libs.versions.ktfmt.get()).googleStyle().configure {
      it.setContinuationIndent(2)
      it.setMaxWidth(120)
    }
  }
  kotlinGradle {
    target("**/*.gradle.kts")
    ktfmt(libs.versions.ktfmt.get()).googleStyle().configure {
      it.setContinuationIndent(2)
      it.setMaxWidth(120)
    }
  }
}

val myJvmArgs = listOf("-Xmx4096m", "-Xms4096m", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC")

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs = myJvmArgs
}

tasks.withType<JavaExec> { jvmArgs = myJvmArgs }

publishing {
  publications.withType<MavenPublication> {
    artifactId = if (name == "kotlinMultiplatform") "kontinuity" else "kontinuity-$name"
  }
}

fun BenchmarkConfiguration.defaults() {
  mode = "AverageTime"
  warmups = 10
  iterations = 10
  outputTimeUnit = TimeUnit.MILLISECONDS.name
}

benchmark {
  targets {
    register("jvmTest") {
      this as JvmBenchmarkTarget
      jmhVersion = "1.37"
    }
    register("jsTest") {
      this as JsBenchmarkTarget
      jsBenchmarksExecutor = JsBenchmarksExecutor.BuiltIn
    }
    register("wasmJsTest")
    register("macosArm64Test")
  }
}
