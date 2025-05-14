package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StateTest {
  @Test
  fun simple() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: State<CounterState>)
    fun incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    context(_: State<CounterState>)
    fun doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      runState(CounterState(0)) {
        incrementCounter()
        doubleCounter()
        doubleCounter()
        get()
      }
    }
    result shouldBe CounterState(4)
  }

  @Test
  fun stack() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    context(_: StackState<CounterState>)
    fun incrementCounter() {
      modifyLast { state -> state.copy(count = state.count + 1) }
    }

    context(_: StackState<CounterState>)
    fun doubleCounter() {
      modifyLast { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      runStackState(CounterState(0)) {
        incrementCounter()
        doubleCounter()
        doubleCounter()
        get()
      }
    }
    result shouldBe listOf(CounterState(0), CounterState(1), CounterState(2), CounterState(4))
  }
}