package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MonadTest {
  data class State<in Region, S, out A>(val run: suspend MultishotScope<Region>.(S) -> Pair<A, S>) {

    companion object {
      fun <S, A> of(a: A): State<Any?, S, A> = State { s -> Pair(a, s) }
    }
  }

  fun <Region, S, A, B> State<Region, S, A>.flatMap(f: suspend MultishotScope<Region>.(A) -> State<Region, S, B>): State<Region, S, B> =
    State { s0 ->
      val (a, s1) = run(s0)
      f(a).run(this, s1)
    }

  context(_: Prompt<InnerRegion, Region, State<Region, S, A>>)
  suspend fun <InnerRegion : Region, Region, S, A, B> MultishotScope<InnerRegion>.bind(state: State<Region, S, B>): B =
    shift { k -> state.flatMap { k(it) } }

  interface StateFunction<Region, S, R> {
    context(_: Prompt<Region2, Region, State<Region, S, R>>)
    suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
  }

  suspend fun <Region, S, R> MultishotScope<Region>.stateReset(body: StateFunction<Region, S, R>): State<Region, S, R> =
    newReset(
      object : PromptFunction<Region, State<Region, S, R>> {
        context(_: Prompt<Region2, Region, State<Region, S, R>>)
        override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): State<Region, S, R> =
          with(body) {
            State.of(invoke())
          }
      }
    )

  @Test
  fun stateMonad() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun incrementCounter(): State<Any?, CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count + 1))
    }

    fun doubleCounter(): State<Any?, CounterState, Unit> = State { state ->
      Pair(Unit, state.copy(count = state.count * 2))
    }

    val result = runCC {
      stateReset(
        object : StateFunction<Any?, CounterState, Unit> {
          context(_: Prompt<Region2, Any?, State<Any?, CounterState, Unit>>)
          override suspend fun <Region2> MultishotScope<Region2>.invoke() {
            bind(incrementCounter())
            bind(doubleCounter())
            bind(doubleCounter())
          }
        }
      )
    }

    result shouldBe runCC {
      incrementCounter().flatMap { doubleCounter().flatMap { doubleCounter() } }.run(this, CounterState(0))
    }
  }

  class Reader<in Region, R, A>(val reader: suspend MultishotScope<Region>.(R) -> A) {
    companion object {
      fun <R, A> of(a: A): Reader<Any?, R, A> = Reader { a }
    }
  }

  fun <Region, R, A, B> Reader<Region, R, A>.flatMap(f: suspend MultishotScope<Region>.(A) -> Reader<Region, R, B>): Reader<Region, R, B> =
    Reader { r0 ->
      val a = reader(r0)
      val reader2 = f(this, a).reader
      reader2(r0)
    }

  context(_: Prompt<Region, OuterRegion, Reader<OuterRegion, R, A>>)
  suspend fun <Region : OuterRegion, OuterRegion, R, A, B> MultishotScope<Region>.bind(reader: Reader<OuterRegion, R, B>): B =
    shift { k -> reader.flatMap { k(it) } }

  interface ReaderFunction<Region, S, R> {
    context(_: Prompt<Region2, Region, Reader<Region, S, R>>)
    suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
  }

  context(_: Prompt<Region2, Region, Reader<Region, S, R>>)
  private suspend fun <Region2 : Region, Region, S, R> MultishotScope<Region2>.invoke(
    body: ReaderFunction<Region, S, R>
  ): R = with(body) {
    invoke()
  }

  suspend fun <Region, R, A> MultishotScope<Region>.readerReset(body: ReaderFunction<Region, R, A>): Reader<Region, R, A> =
    newReset(
      object : PromptFunction<Region, Reader<Region, R, A>> {
        context(_: Prompt<Region2, Region, Reader<Region, R, A>>)
        override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): Reader<Region, R, A> =
          Reader.of(invoke(body))
      }
    )

  @Test
  fun readerMonad() = runTest {
    val one = Reader<Any?, _, _> { input: String -> input.toInt() }
    val sum = runCC {
      readerReset(
        object : ReaderFunction<Any?, String, Int> {
          context(_: Prompt<Region2, Any?, Reader<Any?, String, Int>>)
          override suspend fun <Region2> MultishotScope<Region2>.invoke(): Int =
            bind(one) + bind(one)
        }
      ).reader(this, "1")
    }
    sum shouldBe 2
  }
}