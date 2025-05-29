package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test

class RevStateTest {
  @Test
  fun reverse() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: RevState<Region2, Region, CounterState, Unit>)
    suspend fun <Region2: Region, Region> MultishotScope<Region2>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: RevState<Region2, Region, CounterState, Unit>)
    suspend fun <Region2: Region, Region> MultishotScope<Region2>.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      val (state, _) = runRevState(CounterState(0), object: RevStateFunction<Any?, CounterState, Unit> {
        context(_: RevState<Region2, Any?, CounterState, Unit>)
        override suspend fun <Region2> MultishotScope<Region2>.invoke() {
          doubleCounter()
          doubleCounter()
          incrementCounter()
        }
      })
      state()
    }
    result shouldBe CounterState(4)
  }
}

typealias RevState<Region2, Region, S, R> = Prompt<Region2, Region, Pair<suspend MultishotScope<Region>.() -> S, R>>

context(_: RevState<Region2, Region, S, R>)
suspend fun <Region2 : Region, Region, S, R> MultishotScope<Region2>.modify(f: suspend MultishotScope<Region>.(S) -> S) =
  shift {
  val (s, r) = it(Unit)
    val f2: suspend MultishotScope<Region>.() -> S = { f(s()) }
  f2 to r
}

context(_: RevState<Region2, Region, S, R>)
suspend fun <Region2 : Region, Region, S, R> MultishotScope<Region2>.get(): suspend MultishotScope<Region>.() -> S =
  shift {
    val channel = Channel<suspend MultishotScope<Region>.() -> S>()
  it {
    bridge { channel.receive() }()
  }.also { (s, _) ->
    bridge { channel.send(s) }
  }
}

context(_: RevState<Region2, Region, S, R>)
suspend fun <Region2 : Region, Region, S, R> MultishotScope<Region2>.set(value: S): Unit = shift {
  val (_, r) = it(Unit)
  val s: suspend MultishotScope<Region>.() -> S = { value }
  s to r
}

context(_: RevState<Region2, Region, S, R>)
suspend fun <Region2 : Region, Region, S, R> MultishotScope<Region2>.setLazy(value: suspend MultishotScope<Region>.() -> S): Unit =
  shift {
  val (_, r) = it(Unit)
  value to r
}

interface RevStateFunction<Region, S, R> {
  context(_: RevState<Region2, Region, S, R>)
  suspend operator fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
}

suspend fun <Region, S, R> MultishotScope<Region>.runRevState(
  value: S,
  body: RevStateFunction<Region, S, R>
): Pair<suspend MultishotScope<Region>.() -> S, R> = newReset(
  object : PromptFunction<Region, Pair<suspend MultishotScope<Region>.() -> S, R>> {
    context(_: Prompt<Region2, Region, Pair<suspend MultishotScope<Region>.() -> S, R>>)
    override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): Pair<suspend MultishotScope<Region>.() -> S, R> =
      with(body) {
        Pair({ value }, invoke())
      }
  }
)