package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTestCC {
    // Usage example
    data class CounterState(val count: Int)

    suspend fun RevState<CounterState, Unit>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    suspend fun RevState<CounterState, Unit>.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    runRevState(CounterState(0)) {
      doubleCounter()
      doubleCounter()
      incrementCounter()
    }.first() shouldBe CounterState(4)
  }
}

typealias RevState<S, R> = Prompt<Pair<suspend () -> S, R>>

suspend fun <S, R> RevState<S, R>.modify(f: suspend (S) -> S) = shiftOnce {
  val (s, r) = it(Unit)
  suspend { f(s()) } to r
}

suspend fun <S, R> RevState<S, R>.get(): suspend () -> S = shiftOnce {
  val channel = Channel<suspend () -> S>()
  it {
    channel.receive()()
  }.also { (s, _) ->
    channel.send(s)
  }
}

suspend fun <S, R> RevState<S, R>.set(value: S): Unit = shiftOnce {
  val (_, r) = it(Unit)
  suspend { value } to r
}

suspend fun <S, R> runRevState(value: S, body: suspend RevState<S, R>.() -> R): Pair<suspend () -> S, R> =
  newReset { suspend { value } to body() }
