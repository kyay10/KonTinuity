package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.core.Either.Right
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.kyay10.kontinuity.DelegatingMultishotScope
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.MultishotToken
import io.github.kyay10.kontinuity.ResetDsl
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

@NeedDsl
fun interface Need<R> {
  suspend fun MultishotScope<R>.need(key: Key): Val
}

context(need: Need<R>)
suspend fun <R> MultishotScope<R>.need(key: Key): Val = with(need) { need(key) }

@NeedInputDsl
fun interface NeedInput<R> {
  suspend fun MultishotScope<R>.needInput(key: Key): Val
}

context(needInput: NeedInput<R>)
suspend fun <R> MultishotScope<R>.needInput(key: Key): Val = with(needInput) { needInput(key) }

context(input: NeedInput<R>, need: Need<R>)
suspend fun <R> MultishotScope<R>.example1(key: Key): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * 2
  else -> needInput(key)
}

@DslMarker annotation class NeedDsl
@NeedDsl
class NeedScope<R>(need: Need<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token), Need<R> by need

suspend fun <R> MultishotScope<R>.build(target: Key, tasks: suspend NeedScope<R>.(Key) -> Val): Val =
  scoped(NeedScope({ build(it, tasks) }, token)) { tasks(target) }

context(need: Need<R>)
suspend fun <Ret, R> MultishotScope<R>.memo(block: suspend NeedScope<R>.() -> Ret): Ret {
  val cache = mutableMapOf<Key, Val>()
  return scoped(NeedScope({ key -> cache.getOrPut(key) { need(key) } }, token)) {
    block()
  }
}

context(need: Need<R>, input: NeedInput<R>)
suspend fun <R> MultishotScope<R>.example2(key: Key): Val = when (key) {
  "B1" -> need("A1") + need("A2")
  "B2" -> need("B1") * need("B1")
  else -> needInput(key)
}

data class KeyNotFound(val key: Key)

@DslMarker annotation class NeedInputDsl
@NeedInputDsl
class NeedInputScope<R>(needInput: NeedInput<R>, token: MultishotToken<R>) : DelegatingMultishotScope<R>(token),
  NeedInput<R> by needInput

context(raise: Raise<KeyNotFound>)
suspend fun <Ret, R> MultishotScope<R>.supplyInput(
  store: Map<Key, Val>,
  block: suspend NeedInputScope<R>.() -> Ret
): Ret = scoped(NeedInputScope({ key -> store[key] ?: raise.raise(KeyNotFound(key)) }, token)) { block() }