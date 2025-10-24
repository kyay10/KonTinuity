package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MonadTest {
  data class State<S, out A, in Region>(val run: suspend context(MultishotScope<Region>) (S) -> Pair<A, S>) {

    companion object {
      fun <S, A> of(a: A): State<S, A, Any?> = State { s -> Pair(a, s) }
    }
  }

  fun <S, A, B, Region> State<S, A, Region>.flatMap(f: suspend context(MultishotScope<Region>) (A) -> State<S, B, Region>): State<S, B, Region> =
    State { s0 ->
      val (a, s1) = run(s0)
      f(a).run(s1)
    }

  context(_: MultishotScope<IR>)
  suspend fun <S, A, B, IR, OR> Prompt<State<S, A, OR>, IR, OR>.bind(state: State<S, B, OR>): B =
    shift { k -> state.flatMap { k(it) } }

  context(_: MultishotScope<Region>)
  suspend fun <S, R, Region> stateReset(body: suspend context(NewScope<Region>) Prompt<State<S, R, Region>, NewRegion, Region>.() -> R): State<S, R, Region> =
    newReset { State.of(body(this)) }

  @Test
  fun stateMonad() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): State<CounterState, Unit, Any?> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): State<CounterState, Unit, Any?> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = runCC {
      stateReset {
        bind(incrementCounter())
        bind(doubleCounter())
        bind(doubleCounter())
      }.run(CounterState(0))
    }

    result shouldBe runCC {
      incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }.run(CounterState(0))
    }
  }

  class Reader<R, A, in Region>(val reader: suspend context(MultishotScope<Region>) (R) -> A) {

    companion object {
      fun <R, A> of(a: A): Reader<R, A, Any?> = Reader { a }
    }
  }

  fun <R, A, B, Region> Reader<R, A, Region>.flatMap(f: suspend context(MultishotScope<Region>) (A) -> Reader<R, B, Region>): Reader<R, B, Region> =
    Reader { r0 ->
      val a = reader(r0)
      f(a).reader(r0)
    }

  context(_: MultishotScope<IR>)
  suspend fun <R, A, B, IR, OR> Prompt<Reader<R, A, OR>, IR, OR>.bind(reader: Reader<R, B, OR>): B =
    shift { k -> reader.flatMap { k(it) } }

  context(_: MultishotScope<Region>)
  suspend fun <R, A, Region> readerReset(body: suspend context(NewScope<Region>) Prompt<Reader<R, A, Region>, NewRegion, Region>.() -> A): Reader<R, A, Region> =
    newReset { Reader.of(body(this)) }

  @Test
  fun readerMonad() = runTest {
    val one = Reader<_, _, Any?> { input: String -> input.toInt() }
    val sum = runCC {
      readerReset {
        bind(one) + bind(one)
      }.reader("1")
    }
    sum shouldBe 2
  }
}