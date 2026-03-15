@file:OptIn(ExperimentalWasmDsl::class)

import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.jetbrains.org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext


plugins {
  alias(libs.plugins.kotlinMultiplatform)
  id("module.publication")
  id("org.jetbrains.kotlinx.benchmark") version "0.4.14"
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
      "-Xwhen-guards",
      "-opt-in=kotlin.contracts.ExperimentalContracts",
      "-Xwarning-level=DSL_MARKER_APPLIED_TO_WRONG_TARGET:disabled",
      "-Xwarning-level=ERROR_SUPPRESSION:disabled",
    )
  }
  explicitApi()
  // Matching the targets from Arrow
  jvm {
    compilations.create("benchmark") {
      associateWith(this@jvm.compilations.getByName("test"))
    }
  }
  jvmToolchain(25)
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

    val nonJvmMain by creating { dependsOn(commonMain.get()) }
    val nonJvmTest by creating { dependsOn(commonTest.get()) }

    nativeMain { dependsOn(nonJvmMain) }
    nativeTest { dependsOn(nonJvmTest) }
    jsMain { dependsOn(nonJvmMain) }
    jsTest { dependsOn(nonJvmTest) }
    wasmJsMain { dependsOn(nonJvmMain) }
    wasmJsTest { dependsOn(nonJvmTest) }
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs = listOf(
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-Xmx4096m",
    "-Xms4096m",
    "-XX:+AlwaysPreTouch",
    "-XX:+UseParallelGC"
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
benchmark {
  targets {
    register("jvmBenchmark") {
      this as JvmBenchmarkTarget
      jmhVersion = "1.37"
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
  private val lambdaClasses = setOf(
    "kotlin/coroutines/jvm/internal/SuspendLambda", "kotlin/coroutines/jvm/internal/RestrictedSuspendLambda"
  )
  private val continuationImplName = "kotlin/coroutines/jvm/internal/ContinuationImpl"
  private val continuationClasses = setOf(
    continuationImplName, "kotlin/coroutines/jvm/internal/BaseContinuationImpl"
  ) + lambdaClasses

  private val copyDescriptor =
    Type.getMethodDescriptor(
      Type.getType(Continuation::class.java),
      Type.getType(Continuation::class.java),
      Type.getType(CoroutineContext::class.java)
    )
  val continuationImplConstructor =
    Type.getMethodDescriptor(
      Type.VOID_TYPE,
      Type.getType(Continuation::class.java),
      Type.getType(CoroutineContext::class.java)
    )

  fun transform(bytes: ByteArray): ByteArray? {
    val classNode = ClassNode()
    val classReader = ClassReader(bytes).apply { accept(classNode, 0) }
    if (classNode.superName !in continuationClasses) return null
    if (classNode.methods.any { it.name == "invokeSuspend$\$forInline" }) return null
    val constructor = classNode.methods.single { it.name == "<init>" }
    val fields = classNode.fields

    val classWriter = ClassWriter(classReader, 0)

    val visitor = object : ClassVisitor(Opcodes.ASM5, classWriter) {
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
          interfaces + "io/github/kyay10/kontinuity/MultishotContinuation"
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
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(
              Opcodes.GETFIELD,
              classNode.name,
              field.name,
              field.desc
            )
            visitFieldInsn(
              Opcodes.PUTFIELD,
              classNode.name,
              field.name,
              field.desc
            )
          }
          visitVarInsn(Opcodes.ALOAD, 0)
          val (superCallIndex, superCall) = constructor.instructions.withIndex()
            .first { it.value.opcode == Opcodes.INVOKESPECIAL }
          if (classNode.superName in lambdaClasses) {
            // find arity from super constructor call
            val arityInsn = constructor.instructions[superCallIndex - 2]
            arityInsn.clone(null).accept(this)
          }
          // load completion
          visitVarInsn(Opcodes.ALOAD, 2)
          if (classNode.superName == continuationImplName) {
            // load context
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(
              Opcodes.INVOKESPECIAL,
              continuationImplName,
              "<init>",
              continuationImplConstructor,
              false
            )
          } else {
            // call super constructor
            superCall.clone(null).accept(this)
          }
          visitInsn(Opcodes.RETURN)
          // this and 3 arguments. Either for a field value (might be 2-long if double or long) or arity + completion
          visitMaxs(3, 4)
          visitEnd()
        }
        // add copy method
        visitMethod(ACC_PUBLIC or ACC_SYNTHETIC, "copy", copyDescriptor, null, null).apply {
          visitParameter("completion", 0)
          visitCode()
          // create new instance of this class
          visitTypeInsn(Opcodes.NEW, classNode.name)
          visitInsn(Opcodes.DUP)
          // Call copy constructor
          visitVarInsn(Opcodes.ALOAD, 0)
          visitVarInsn(Opcodes.ALOAD, 1)
          visitVarInsn(Opcodes.ALOAD, 2)
          visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            classNode.name,
            "<init>",
            copyConstructorDescriptor,
            false
          )
          visitInsn(Opcodes.ARETURN)
          visitMaxs(5, 3)
          visitEnd()
        }
        super.visitEnd()
      }
    }

    classReader.accept(visitor, 0)
    return classWriter.toByteArray()
  }
}