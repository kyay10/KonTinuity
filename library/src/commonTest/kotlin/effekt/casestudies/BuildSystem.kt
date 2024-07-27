package effekt.casestudies

import arrow.core.Either.Right
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.kotest.matchers.shouldBe
import runTestCC
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

fun interface Need {
  suspend fun need(key: Key): Val
}

fun interface NeedInput {
  suspend fun needInput(key: Key): Val
}

suspend fun Need.example1(key: Key, input: NeedInput): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * 2
  else -> input.needInput(key)
}

suspend fun build(target: Key, tasks: suspend Need.(Key) -> Val): Val = Need { build(it, tasks) }.tasks(target)

suspend fun <R> Need.memo(block: suspend Need.() -> R): R {
  val cache = mutableMapOf<Key, Val>()
  return Need { key -> cache.getOrPut(key) { need(key) } }.block()
}

suspend fun Need.example2(key: Key, input: NeedInput): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * need("B1")
  else -> input.needInput(key)
}

data class KeyNotFound(val key: Key)

suspend fun <R> Raise<KeyNotFound>.supplyInput(store: Map<Key, Val>, block: suspend NeedInput.() -> R): R =
  NeedInput { key -> store[key] ?: raise(KeyNotFound(key)) }.block()