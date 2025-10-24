package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: MultishotScope<IR>)
    suspend fun <IR, OR> RevState<CounterState, Unit, IR, OR>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: MultishotScope<IR>)
    suspend fun <IR, OR> RevState<CounterState, Unit, IR, OR>.doubleCounter() {
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

typealias RevState<S, R, IR, OR> = Prompt<Pair<suspend context(MultishotScope<OR>) () -> S, R>, IR, OR>

context(_: MultishotScope<IR>)
suspend fun <S, R, IR, OR> RevState<S, R, IR, OR>.modify(f: suspend context(MultishotScope<OR>) (S) -> S) = shift {
  val (s, r) = it(Unit)
  val fs: suspend context(MultishotScope<OR>) () -> S = { f(s()) }
  fs to r
}

context(_: MultishotScope<IR>)
suspend fun <S, R, IR, OR> RevState<S, R, IR, OR>.get(): suspend context(MultishotScope<OR>) () -> S = shift {
  val channel = Channel<suspend context(MultishotScope<OR>) () -> S>()
  it(suspend {
    bridge { channel.receive() }()
  }).also { (s, _) ->
    bridge { channel.send(s) }
  }
}

context(_: MultishotScope<IR>)
suspend fun <S, R, IR, OR> RevState<S, R, IR, OR>.set(value: S): Unit = shift {
  val (_, r) = it(Unit)
  val f: suspend context(MultishotScope<OR>) () -> S = { value }
  f to r
}

context(_: MultishotScope<IR>)
suspend fun <S, R, IR, OR> RevState<S, R, IR, OR>.setLazy(value: suspend context(MultishotScope<OR>) () -> S): Unit = shift {
  val (_, r) = it(Unit)
  value to r
}

context(_: MultishotScope<Region>)
suspend fun <S, R, Region> runRevState(
  value: S,
  body: suspend context(NewScope<Region>) RevState<S, R, NewRegion, Region>.() -> R
): Pair<suspend context(MultishotScope<Region>) () -> S, R> = newReset {
  val f: suspend context(MultishotScope<Region>) () -> S = { value }
  f to body()
}
