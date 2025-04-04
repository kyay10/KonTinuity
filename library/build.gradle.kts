import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("module.publication")
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
  }
  explicitApi()
  applyDefaultHierarchyTemplate()
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_1_8
    }
  }
  js {
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

  sourceSets {
    val commonMain by getting {
      repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
        maven("https://redirector.kotlinlang.org/maven/dev")
      }
      dependencies {
        implementation(libs.arrow.core)
        implementation(libs.arrow.fx.coroutines)
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

    val nonJvmMain by creating {
      dependsOn(commonMain)
    }
    jsMain {
      dependsOn(nonJvmMain)
    }
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