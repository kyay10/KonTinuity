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
          example1(it)
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
          example2(it)
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
            example2(it)
          }
        }
      }
    } shouldBe Right(900)
    accessed shouldBe listOf("B2", "B1", "A1", "A2")
  }
}

typealias Key = String
typealias Val = Int

fun interface Need {
  suspend fun MultishotScope.need(key: Key): Val
}
context(need: Need)
suspend fun MultishotScope.need(key: Key): Val = with(need) { need(key) }

fun interface NeedInput {
  suspend fun MultishotScope.needInput(key: Key): Val
}
context(needInput: NeedInput)
suspend fun MultishotScope.needInput(key: Key): Val = with(needInput) { needInput(key) }

context(input: NeedInput, need: Need)
suspend fun MultishotScope.example1(key: Key): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * 2
  else -> needInput(key)
}

suspend fun MultishotScope.build(target: Key, tasks: suspend context(Need) MultishotScope.(Key) -> Val): Val = tasks(Need { build(it, tasks) }, this, target)

context(need: Need)
suspend fun <R> MultishotScope.memo(block: suspend context(Need) MultishotScope.() -> R): R {
  val cache = mutableMapOf<Key, Val>()
  return block(Need { key -> cache.getOrPut(key) { need(key) } }, this)
}

context(need: Need, input: NeedInput)
suspend fun MultishotScope.example2(key: Key): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * need("B1")
  else -> needInput(key)
}

data class KeyNotFound(val key: Key)

context(raise: Raise<KeyNotFound>)
suspend fun <R> MultishotScope.supplyInput(store: Map<Key, Val>, block: suspend context(NeedInput) MultishotScope.() -> R): R =
  block(NeedInput { key -> store[key] ?: raise.raise(KeyNotFound(key)) }, this)