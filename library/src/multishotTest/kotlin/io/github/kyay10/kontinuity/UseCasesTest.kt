package io.github.kyay10.kontinuity

import arrow.core.Option
import arrow.core.Some
import arrow.core.some
import kotlin.test.Test

class UseCasesTest {
  suspend fun <R> String.parseAll(parser: suspend context(Amb, Exc, Input) () -> R): List<R> = nondet {
    stringReader { parser() }
  }

  suspend fun <R> String.parseBacktrack(parser: suspend context(Amb, Exc, Input) () -> R): Option<R> = backtrack {
    stringReader { parser() }
  }

  @Test
  fun example() = runTestCC {
    "123".parseAll { number() } shouldEq listOf(123, 12, 1)
    "123".parseBacktrack { number() } shouldEq Some(123)
  }
}

context(_: Amb, _: Exc, _: Input)
suspend fun digit(): Int = accept(Char::isDigit).digitToInt()

context(_: Amb, _: Exc, _: Input)
suspend fun number(): Int {
  var res = digit()
  while (flip()) res = res * 10 + digit()
  return res
}

context(_: Exc)
suspend fun <R> String.stringReader(block: suspend Input.() -> R): R =
  runState(0) { block { get(value++.also { ensure(it < length) }) } }

suspend fun <E> nondet(block: suspend context(Amb, Exc) () -> E): List<E> = handle { listOf(block(amb, exc)) }

suspend fun <R> backtrack(block: suspend context(Amb, Exc) () -> R): Option<R> = handle { block(amb, exc).some() }

val <E> Handler<Option<E>>.amb: Amb
  get() = Amb { use { resume -> resume(true).handleErrorWith { resume(false) } } }
