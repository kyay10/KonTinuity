@file:OptIn(ExperimentalWasmDsl::class)

import kotlinx.benchmark.gradle.JsBenchmarkTarget
import kotlinx.benchmark.gradle.JsBenchmarksExecutor
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes.*
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("module.publication")
  id("org.jetbrains.kotlinx.benchmark") version "0.4.16"
}

repositories {
  mavenCentral()
  maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
  maven("https://redirector.kotlinlang.org/maven/dev")
  mavenLocal()
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xcontext-parameters",
      "-Xexpect-actual-classes",
      "-opt-in=kotlin.contracts.ExperimentalContracts",
      "-Xwarning-level=DSL_MARKER_APPLIED_TO_WRONG_TARGET:disabled",
      "-Xwarning-level=ERROR_SUPPRESSION:disabled",
      "-Xreturn-value-checker=full",
    )
  }
  explicitApi()
  // Matching the targets from Arrow
  jvm()
  jvmToolchain(25)
  js(IR) {
    compilerOptions {
      freeCompilerArgs.add("-Xes-generators=false")
      target = "es2015"
    }
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
    val multishotMain by creating { dependsOn(commonMain.get()) }
    val multishotTest by creating { dependsOn(commonTest.get()) }

    val nonJvmMain by creating { dependsOn(commonMain.get()) }
    val nonJvmTest by creating { dependsOn(commonTest.get()) }

    val nonMultishotMain by creating { dependsOn(nonJvmMain) }

    jvmMain { dependsOn(multishotMain) }
    jvmTest { dependsOn(multishotTest) }

    nativeMain { dependsOn(nonMultishotMain) }
    nativeTest { dependsOn(nonJvmTest) }
    jsMain {
      dependsOn(nonJvmMain)
      dependsOn(multishotMain)
      dependencies {
        implementation(kotlinWrappers.js)
        implementation(kotlinWrappers.jsCore)
      }
    }
    jsTest {
      dependsOn(nonJvmTest)
      dependsOn(multishotTest)
    }
    wasmJsMain { dependsOn(nonMultishotMain) }
    wasmJsTest { dependsOn(nonJvmTest) }
  }
}

val myJvmArgs = listOf(
  "-Xmx4096m",
  "-Xms4096m",
  "-XX:+AlwaysPreTouch",
  "-XX:+UseParallelGC",
)

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs = myJvmArgs
}

tasks.withType<JavaExec> { jvmArgs = myJvmArgs }

publishing {
  publications.withType<MavenPublication> {
    artifactId = if (name == "kotlinMultiplatform") {
      "kontinuity"
    } else {
      "kontinuity-$name"
    }
  }
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
  configurations {
    register("skynet") {
      include(".*Skynet.*")
      exclude(".*Coroutines.*")
      mode = "AverageTime"
      warmups = 1
      iterations = 1
      iterationTime = 10
      iterationTimeUnit = TimeUnit.SECONDS.name
      outputTimeUnit = TimeUnit.MILLISECONDS.name
    }
    register("skynetScheduler") {
      include(".*Skynet.*Scheduler.*")
      mode = "AverageTime"
      warmups = 1
      iterations = 1
      iterationTime = 10
      iterationTimeUnit = TimeUnit.SECONDS.name
      outputTimeUnit = TimeUnit.MILLISECONDS.name
    }
    register("sharing") {
      include(".*Sharing.*")
      mode = "AverageTime"
      warmups = 10
      iterations = 10
      iterationTime = 1
      iterationTimeUnit = TimeUnit.SECONDS.name
      outputTimeUnit = TimeUnit.MILLISECONDS.name
    }
  }
}

// Plugin
tasks.withType<KotlinJvmCompile>().configureEach {
  doLast("multishotOptimize") {
    destinationDirectory.asFileTree
      .filter { it.isFile && it.name.endsWith(".class") }
      .forEach { file ->
        MultishotTransform.transform(file.readBytes())?.let(file::writeBytes)
      }
  }
}

object MultishotTransform {
  private const val COROUTINES_PKG = "kotlin/coroutines/jvm/internal"
  private const val BASE_CONTINUATION_IMPL = "$COROUTINES_PKG/BaseContinuationImpl"
  private const val CONTINUATION_IMPL = "$COROUTINES_PKG/ContinuationImpl"
  private val lambdaClasses = setOf("$COROUTINES_PKG/SuspendLambda", "$COROUTINES_PKG/RestrictedSuspendLambda")
  private val continuationClasses = setOf(CONTINUATION_IMPL, BASE_CONTINUATION_IMPL) + lambdaClasses

  private val invokeCopiedDescriptor = Type.getMethodDescriptor(
    Type.getType(Any::class.java),
    Type.getType(Continuation::class.java),
    Type.getType(CoroutineContext::class.java),
    Type.getType(Any::class.java),
  )

  private val invokeSuspendDescriptor: String = Type.getMethodDescriptor(
    Type.getType(Any::class.java),
    Type.getType(Any::class.java),
  )

  private val continuationImplConstructor: String = Type.getMethodDescriptor(
    Type.VOID_TYPE,
    Type.getType(Continuation::class.java),
    Type.getType(CoroutineContext::class.java)
  )

  fun transform(bytes: ByteArray): ByteArray? {
    val classNode = ClassNode()
    val classReader = ClassReader(bytes).apply { accept(classNode, 0) }
    if (classNode.superName !in continuationClasses) return null
    if (classNode.methods.any { it.name == $$$"invokeSuspend$$forInline" }) return null
    val constructor = classNode.methods.single { it.name == "<init>" }
    val fields = classNode.fields.filter { it.access and ACC_STATIC == 0 }

    val classWriter = ClassWriter(classReader, 0)

    val visitor = object : ClassVisitor(ASM5, classWriter) {
      override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<out String?>
      ) {
        @Suppress("UNCHECKED_CAST")
        interfaces as Array<String?>
        super.visit(
          version,
          access,
          name,
          signature,
          superName,
          interfaces + "io/github/kyay10/kontinuity/internal/MultishotContinuation"
        )
      }

      override fun visitEnd() {
        // No chance that this'll be a pre-existing constructor
        val copyConstructorDescriptor =
          Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Type.getObjectType(classNode.name),
            Type.getType(Continuation::class.java),
            Type.getType(CoroutineContext::class.java)
          )
        // add copy constructor
        visitMethod(ACC_PUBLIC or ACC_SYNTHETIC, "<init>", copyConstructorDescriptor, null, null).apply {
          visitParameter("template", 0)
          visitParameter("completion", 0)
          visitParameter("context", 0)
          visitCode()
          // copy fields from this to created instance
          for (field in fields) {
            visitVarInsn(ALOAD, 0)
            visitVarInsn(ALOAD, 1)
            visitFieldInsn(GETFIELD, classNode.name, field.name, field.desc)
            visitFieldInsn(PUTFIELD, classNode.name, field.name, field.desc)
          }
          visitVarInsn(ALOAD, 0)
          val (superCallIndex, superCall) = constructor.instructions.withIndex()
            .first { it.value.opcode == INVOKESPECIAL }
          if (classNode.superName in lambdaClasses) {
            // find arity from super constructor call
            val arityInsn = constructor.instructions[superCallIndex - 2]
            arityInsn.clone(null).accept(this)
          }
          // load completion
          visitVarInsn(ALOAD, 2)
          if (classNode.superName == CONTINUATION_IMPL) {
            // load context
            visitVarInsn(ALOAD, 3)
            visitMethodInsn(INVOKESPECIAL, CONTINUATION_IMPL, "<init>", continuationImplConstructor, false)
          } else {
            // call super constructor
            superCall.clone(null).accept(this)
          }
          visitInsn(RETURN)
          // this and 3 arguments. Either for a field value (might be 2-long if double or long) or arity + completion
          visitMaxs(3, 4)
          visitEnd()
        }
        // add invokeCopied method
        visitMethod(
          ACC_PUBLIC or ACC_SYNTHETIC or ACC_FINAL,
          "invokeCopied",
          invokeCopiedDescriptor,
          null,
          null
        ).apply {
          visitParameter("completion", 0)
          visitParameter("context", 0)
          visitParameter("result", 0)
          visitCode()
          // create new instance of this class
          visitTypeInsn(NEW, classNode.name)
          visitInsn(DUP)
          // Call copy constructor
          visitVarInsn(ALOAD, 0)
          visitVarInsn(ALOAD, 1)
          visitVarInsn(ALOAD, 2)
          visitMethodInsn(INVOKESPECIAL, classNode.name, "<init>", copyConstructorDescriptor, false)
          visitVarInsn(ALOAD, 3)
          visitMethodInsn(INVOKEVIRTUAL, classNode.name, "invokeSuspend", invokeSuspendDescriptor, false)
          visitInsn(ARETURN)
          visitMaxs(5, 4)
          visitEnd()
        }
        super.visitEnd()
      }
    }

    classReader.accept(visitor, 0)
    return classWriter.toByteArray()
  }
}