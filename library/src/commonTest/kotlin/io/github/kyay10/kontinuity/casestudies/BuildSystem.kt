package io.github.kyay10.kontinuity.casestudies

import arrow.core.Either.Right
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import io.github.kyay10.kontinuity.runMapBuilder
import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.shouldEq
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
    } shouldEq Right(60)
    accessed shouldEq listOf("B2", "B1", "A1", "A2")
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
    } shouldEq Right(900)
    accessed shouldEq listOf("B2", "B1", "A1", "A2", "B1", "A1", "A2")
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
    } shouldEq Right(900)
    accessed shouldEq listOf("B2", "B1", "A1", "A2")
  }
}

typealias Key = String

typealias Val = Int

fun interface Need {
  suspend fun need(key: Key): Val
}

context(need: Need)
suspend fun need(key: Key): Val = need.need(key)

fun interface NeedInput {
  fun needInput(key: Key): Val
}

context(needInput: NeedInput)
fun needInput(key: Key): Val = needInput.needInput(key)

context(_: Need, _: NeedInput)
suspend fun example1(key: Key): Val =
  when (key) {
    "B1" -> need("A1") + need("A2")
    "B2" -> need("B1") * 2
    else -> needInput(key)
  }

suspend fun build(target: Key, tasks: suspend Need.(Key) -> Val): Val = Need { build(it, tasks) }.tasks(target)

suspend fun <R> Need.memo(block: suspend Need.() -> R): R = runMapBuilder {
  block { key -> getOrPut(key) { need(key) } }
}

context(_: Need, _: NeedInput)
suspend fun example2(key: Key): Val =
  when (key) {
    "B1" -> need("A1") + need("A2")
    "B2" -> need("B1") * need("B1")
    else -> needInput(key)
  }

data class KeyNotFound(val key: Key)

suspend fun <R> Raise<KeyNotFound>.supplyInput(store: Map<Key, Val>, block: suspend NeedInput.() -> R): R =
  block { key ->
    ensureNotNull(store[key]) { KeyNotFound(key) }
  }
