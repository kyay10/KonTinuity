package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StateTest {
  @Test
  fun simple() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun State<CounterState>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    fun State<CounterState>.doubleCounter() {
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

    fun StackState<CounterState>.incrementCounter() {
      modifyLast { state -> state.copy(count = state.count + 1) }
    }

    fun StackState<CounterState>.doubleCounter() {
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