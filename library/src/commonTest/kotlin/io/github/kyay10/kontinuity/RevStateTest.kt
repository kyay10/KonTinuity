package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: MultishotScope)
    suspend fun RevState<CounterState, Unit>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: MultishotScope)
    suspend fun RevState<CounterState, Unit>.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      runRevState(CounterState(0)) {
        doubleCounter()
        doubleCounter()
        incrementCounter()
      }.first()
    }
    result shouldBe CounterState(4)
  }
}

typealias RevState<S, R> = Prompt<Pair<suspend context(MultishotScope) () -> S, R>>

context(_: MultishotScope)
suspend fun <S, R> RevState<S, R>.modify(f: suspend context(MultishotScope) (S) -> S) = shift {
  val (s, r) = it(Unit)
  val fs: suspend context(MultishotScope) () -> S = { f(s()) }
  fs to r
}

context(_: MultishotScope)
suspend fun <S, R> RevState<S, R>.get(): suspend context(MultishotScope) () -> S = shift {
  val channel = Channel<suspend context(MultishotScope) () -> S>()
  it(suspend {
    bridge { channel.receive() }()
  }).also { (s, _) ->
    bridge { channel.send(s) }
  }
}

context(_: MultishotScope)
suspend fun <S, R> RevState<S, R>.set(value: S): Unit = shift {
  val (_, r) = it(Unit)
  val f: suspend context(MultishotScope) () -> S = { value }
  f to r
}

context(_: MultishotScope)
suspend fun <S, R> RevState<S, R>.setLazy(value: suspend context(MultishotScope) () -> S): Unit = shift {
  val (_, r) = it(Unit)
  value to r
}

context(_: MultishotScope)
suspend fun <S, R> runRevState(
  value: S,
  body: suspend context(MultishotScope) RevState<S, R>.() -> R
): Pair<suspend context(MultishotScope) () -> S, R> = newReset {
  val f: suspend context(MultishotScope) () -> S = { value }
  f to body()
}
