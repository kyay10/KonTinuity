package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MonadTest {
  data class State<S, out A, in Region>(val run: suspend MultishotScope<Region>.(S) -> Pair<A, S>) {

    companion object {
      fun <S, A> of(a: A): State<S, A, Any?> = State { s -> Pair(a, s) }
    }
  }

  fun <S, A, B, Region> State<S, A, Region>.flatMap(f: suspend MultishotScope<Region>.(A) -> State<S, B, Region>): State<S, B, Region> =
    State { s0 ->
      val (a, s1) = run(s0)
      f(a).run(this, s1)
    }

  context(_: Prompt<State<S, A, OR>, IR, OR>)
  suspend fun <S, A, B, IR : OR, OR> MultishotScope<IR>.bind(state: State<S, B, OR>): B =
    shift { k -> state.flatMap { k(it) } }

  suspend fun <S, R, Region> MultishotScope<Region>.stateReset(body: suspend PromptCont<State<S, R, Region>, *, Region>.() -> R): State<S, R, Region> =
    newReset { State.of(body()) }

  @Test
  fun stateMonad() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    val incrementCounter: State<CounterState, Unit, Any?> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    val doubleCounter: State<CounterState, Unit, Any?> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = runCC {
      suspend fun <IR> PromptCont<State<CounterState, Unit, Any?>, IR, Any?>.function() {
        bind(incrementCounter)
        bind(doubleCounter)
        bind(doubleCounter)
      }
      stateReset { function() }.run(this, CounterState(0))
    }

    result shouldBe runCC {
      incrementCounter.flatMap { doubleCounter.flatMap { doubleCounter } }.run(this, CounterState(0))
    }
  }

  class Reader<R, A, in Region>(val reader: suspend MultishotScope<Region>.(R) -> A) {
    companion object {
      fun <R, A> of(a: A): Reader<R, A, Any?> = Reader { a }
    }
  }

  fun <R, A, B, Region> Reader<R, A, Region>.flatMap(f: suspend MultishotScope<Region>.(A) -> Reader<R, B, Region>): Reader<R, B, Region> =
    Reader { r0 ->
      val a = reader(r0)
      val reader2 = f(this, a).reader
      reader2(r0)
    }

  context(_: Prompt<Reader<R, A, OR>, IR, OR>)
  suspend fun <R, A, B, IR : OR, OR> MultishotScope<IR>.bind(reader: Reader<R, B, OR>): B =
    shift { k -> reader.flatMap { k(it) } }

  suspend fun <R, A, Region> MultishotScope<Region>.readerReset(body: suspend PromptCont<Reader<R, A, Region>, *, Region>.() -> A): Reader<R, A, Region> =
    newReset { Reader.of(body(this)) }

  @Test
  fun readerMonad() = runTest {
    val one = Reader<_, _, Any?> { input: String -> input.toInt() }
    val sum = runCC {
      suspend fun <IR> PromptCont<Reader<String, Int, Any?>, IR, Any?>.function() = bind(one) + bind(one)
      readerReset { function() }.reader(this, "1")
    }
    sum shouldBe 2
  }
}