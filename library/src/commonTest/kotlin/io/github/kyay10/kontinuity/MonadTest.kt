package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MonadTest {
  data class State<S, out A>(val run: suspend MultishotScope.(S) -> Pair<A, S>) {
    fun <B> flatMap(f: suspend MultishotScope.(A) -> State<S, B>): State<S, B> = State { s0 ->
      val (a, s1) = run(s0)
      f(a).run(this, s1)
    }

    companion object {
      fun <S, A> of(a: A): State<S, A> = State { s -> Pair(a, s) }
    }
  }

  context(_: Prompt<State<S, A>>)
  suspend fun <S, A, B> MultishotScope.bind(state: State<S, B>): B = shift { k -> state.flatMap { k(it) } }

  suspend fun <S, R> MultishotScope.stateReset(body: suspend context(Prompt<State<S, R>>) MultishotScope.() -> R): State<S, R> =
    newReset { State.of(body(given<Prompt<State<S, R>>>(), this)) }

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
      }.run(this, CounterState(0))
    }

    result shouldBe runCC {
      incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }.run(this, CounterState(0))
    }
  }

  class Reader<R, A>(val reader: suspend MultishotScope.(R) -> A) {
    fun <B> flatMap(f: suspend MultishotScope.(A) -> Reader<R, B>): Reader<R, B> = Reader { r0 ->
      val a = reader(r0)
      val reader2 = f(this,a).reader
      reader2(r0)
    }

    companion object {
      fun <R, A> of(a: A): Reader<R, A> = Reader { a }
    }
  }

  context(_: Prompt<Reader<R, A>>)
  suspend fun <R, A, B> MultishotScope.bind(reader: Reader<R, B>): B = shift { k -> reader.flatMap { k(it) } }

  suspend fun <R, A> MultishotScope.readerReset(body: suspend context(Prompt<Reader<R, A>>) MultishotScope.() -> A): Reader<R, A> =
    newReset { Reader.of(body(given<Prompt<Reader<R, A>>>(), this)) }

  @Test
  fun readerMonad() = runTest {
    val one = Reader { input: String -> input.toInt() }
    val sum = runCC {
      readerReset {
        bind(one) + bind(one)
      }.reader(this, "1")
    }
    sum shouldBe 2
  }
}