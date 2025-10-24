package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either.Right
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class BuildSystemTest {
  val inputs = mapOf("A1" to 10, "A2" to 20)

  @Test
  fun ex1() = runTestCC {
    val accessed = mutableListOf<Key>()
    either {
      supplyInput(inputs) {
        build("B2") {
          accessed += it
          example1(it, this@supplyInput)
        }
      }
    } shouldBe Right(60)
    accessed shouldBe listOf("B2", "B1", "A1", "A2")
  }

  @Test
  fun ex2() = runTestCC {
    val accessed = mutableListOf<Key>()
    either {
      supplyInput(inputs) {
        build("B2") {
          accessed += it
          example2(it, this@supplyInput)
        }
      }
    } shouldBe Right(900)
    accessed shouldBe listOf("B2", "B1", "A1", "A2", "B1", "A1", "A2")
  }

  @Test
  fun ex3() = runTestCC {
    val accessed = mutableListOf<Key>()
    either {
      supplyInput(inputs) {
        build("B2") {
          memo {
            accessed += it
            example2(it, this@supplyInput)
          }
        }
      }
    } shouldBe Right(900)
    accessed shouldBe listOf("B2", "B1", "A1", "A2")
  }
}

typealias Key = String
typealias Val = Int

fun interface Need<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun need(key: Key): Val
}

fun interface NeedInput<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun needInput(key: Key): Val
}

context(_: MultishotScope<Region>)
suspend fun <Region> Need<Region>.example1(key: Key, input: NeedInput<Region>): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * 2
  else -> input.needInput(key)
}

context(_: MultishotScope<Region>)
suspend fun <Region> build(target: Key, tasks: suspend context(MultishotScope<Region>) Need<Region>.(Key) -> Val): Val =
  Need { build(it, tasks) }.tasks(target)

inline fun <R, Region> Need<Region>.memo(block: Need<Region>.() -> R): R {
  val cache = mutableMapOf<Key, Val>()
  return Need { key -> cache.getOrPut(key) { need(key) } }.block()
}

context(_: MultishotScope<Region>)
suspend fun <Region> Need<Region>.example2(key: Key, input: NeedInput<Region>): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * need("B1")
  else -> input.needInput(key)
}

data class KeyNotFound(val key: Key)

inline fun <R> Raise<KeyNotFound>.supplyInput(
  store: Map<Key, Val>,
  block: NeedInput<Any?>.() -> R
): R =
  NeedInput<Any?> { key -> store[key] ?: raise(KeyNotFound(key)) }.block()