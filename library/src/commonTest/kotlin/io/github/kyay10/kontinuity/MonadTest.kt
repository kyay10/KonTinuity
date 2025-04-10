package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MonadTest {
  data class State<S, out A>(val run: suspend (S) -> Pair<A, S>) {
    fun <B> flatMap(f: suspend (A) -> State<S, B>): State<S, B> = State { s0 ->
      val (a, s1) = run(s0)
      f(a).run(s1)
    }

    companion object {
      fun <S, A> of(a: A): State<S, A> = State { s -> Pair(a, s) }
    }
  }

  suspend fun <S, A, B> Prompt<State<S, A>>.bind(state: State<S, B>): B = shift { k -> state.flatMap { k(it) } }

  suspend fun <S, R> stateReset(body: suspend Prompt<State<S, R>>.() -> R): State<S, R> =
    newReset { State.of(body(this)) }

  @Test
  fun stateMonad() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = runCC {
      stateReset {
        bind(incrementCounter())
        bind(doubleCounter())
        bind(doubleCounter())
      }.run(CounterState(0))
    }

    result shouldBe incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }.run(CounterState(0))
  }

  class Reader<R, A>(val reader: suspend (R) -> A) {
    fun <B> flatMap(f: suspend (A) -> Reader<R, B>): Reader<R, B> = Reader { r0 ->
      val a = reader(r0)
      f(a).reader(r0)
    }

    companion object {
      fun <R, A> of(a: A): Reader<R, A> = Reader { a }
    }
  }

  suspend fun <R, A, B> Prompt<Reader<R, A>>.bind(reader: Reader<R, B>): B = shift { k -> reader.flatMap { k(it) } }

  suspend fun <R, A> readerReset(body: suspend Prompt<Reader<R, A>>.() -> A): Reader<R, A> =
    newReset { Reader.of(body(this)) }

  @Test
  fun readerMonad() = runTest {
    val one: Reader<String, Int> = Reader { input -> input.toInt() }
    val sum = runCC {
      readerReset {
        bind(one) + bind(one)
      }.reader("1")
    }
    sum shouldBe 2
  }
}