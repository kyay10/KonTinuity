package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: RevState<CounterState, Unit>)
    suspend fun MultishotScope.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: RevState<CounterState, Unit>)
    suspend fun MultishotScope.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      runRevState(CounterState(0)) {
        doubleCounter()
        doubleCounter()
        incrementCounter()
      }.first(this)
    }
    result shouldBe CounterState(4)
  }
}

typealias RevState<S, R> = Prompt<Pair<suspend MultishotScope.() -> S, R>>

context(_: RevState<S, R>)
suspend fun <S, R> MultishotScope.modify(f: suspend MultishotScope.(S) -> S) = shift {
  val (s, r) = it(Unit)
  val f2: suspend MultishotScope.() -> S = { f(s()) }
  f2 to r
}

context(_: RevState<S, R>)
suspend fun <S, R> MultishotScope.get(): suspend MultishotScope.() -> S = shift {
  val channel = Channel<suspend MultishotScope.() -> S>()
  it {
    bridge { channel.receive() }()
  }.also { (s, _) ->
    bridge { channel.send(s) }
  }
}

context(_: RevState<S, R>)
suspend fun <S, R> MultishotScope.set(value: S): Unit = shift {
  val (_, r) = it(Unit)
  val s: suspend MultishotScope.() -> S = { value }
  s to r
}

context(_: RevState<S, R>)
suspend fun <S, R> MultishotScope.setLazy(value: suspend MultishotScope.() -> S): Unit = shift {
  val (_, r) = it(Unit)
  value to r
}

suspend fun <S, R> MultishotScope.runRevState(
  value: S,
  body: suspend context(RevState<S, R>) MultishotScope.() -> R
): Pair<suspend MultishotScope.() -> S, R> = newReset {
  Pair<suspend MultishotScope.() -> S, R>({ value }, body())
}
