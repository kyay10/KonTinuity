package io.github.kyay10.kontinuity

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

  suspend fun <S, A, B> Handler<State<S, A>>.bind(state: State<S, B>): B = useOnce { k -> state.flatMap { k(it) } }

  suspend fun <S, R> stateReset(body: suspend Handler<State<S, R>>.() -> R): State<S, R> =
    handle { State.of(body(this)) }

  @Test
  fun stateMonad() = runTestCC {
    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): State<CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = stateReset {
      bind(incrementCounter())
      bind(doubleCounter())
      bind(doubleCounter())
    }.run(CounterState(0))

    result shouldEq incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }.run(CounterState(0))
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

  suspend fun <R, A, B> Handler<Reader<R, A>>.bind(reader: Reader<R, B>): B = useOnce { k -> reader.flatMap { k(it) } }

  suspend fun <R, A> readerReset(body: suspend Handler<Reader<R, A>>.() -> A): Reader<R, A> =
    handle { Reader.of(body(this)) }

  @Test
  fun readerMonad() = runTestCC {
    val one = Reader { input: String -> input.toInt() }
    readerReset {
      bind(one) + bind(one)
    }.reader("1") shouldEq 2
  }
}