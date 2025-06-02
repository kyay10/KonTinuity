package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: RevState<CounterState, Unit, IR, OR>)
    suspend fun <IR : OR, OR> MultishotScope<IR>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: RevState<CounterState, Unit, IR, OR>)
    suspend fun <IR : OR, OR> MultishotScope<IR>.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      suspend fun <IR> PromptCont<Pair<suspend MultishotScope<Any?>.() -> CounterState, Unit>, IR, Any?>.function() {
        doubleCounter()
        doubleCounter()
        incrementCounter()
      }
      runRevState(CounterState(0)) { function() }.first(this)
    }
    result shouldBe CounterState(4)
  }
}

typealias RevState<S, R, IR, OR> = Prompt<Pair<suspend MultishotScope<OR>.() -> S, R>, IR, OR>

context(_: RevState<S, R, IR, OR>)
suspend fun <S, R, IR : OR, OR> MultishotScope<IR>.modify(f: suspend MultishotScope<OR>.(S) -> S) = shift {
  val (s, r) = it(Unit)
  val f2: suspend MultishotScope<OR>.() -> S = { f(s()) }
  f2 to r
}

context(_: RevState<S, R, IR, OR>)
suspend fun <S, R, IR : OR, OR> MultishotScope<IR>.get(): suspend MultishotScope<IR>.() -> S = shift {
  val channel = Channel<suspend MultishotScope<IR>.() -> S>()
  it {
    bridge { channel.receive() }()
  }.also { (s, _) ->
    bridge { channel.send(s) }
  }
}

context(_: RevState<S, R, IR, OR>)
suspend fun <S, R, IR : OR, OR> MultishotScope<IR>.set(value: S): Unit = shift {
  val (_, r) = it(Unit)
  val s: suspend MultishotScope<OR>.() -> S = { value }
  s to r
}

context(_: RevState<S, R, IR, OR>)
suspend fun <S, R, IR : OR, OR> MultishotScope<IR>.setLazy(value: suspend MultishotScope<OR>.() -> S): Unit = shift {
  val (_, r) = it(Unit)
  value to r
}

suspend fun <S, R, Region> MultishotScope<Region>.runRevState(
  value: S,
  body: suspend PromptCont<Pair<suspend MultishotScope<Region>.() -> S, R>, *, Region>.() -> R
): Pair<suspend MultishotScope<Region>.() -> S, R> = newReset {
  Pair<suspend MultishotScope<Region>.() -> S, R>({ value }, body())
}
