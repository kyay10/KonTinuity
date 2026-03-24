package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StateTest {
  @Test
  fun simple() = runTestCC {
    // Usage example
    data class CounterState(val count: Int)

    fun State<CounterState>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    fun State<CounterState>.doubleCounter() {
      modify { state -> state.copy(count = state.count * 2) }
    }

    val result = runState(CounterState(0)) {
      incrementCounter()
      doubleCounter()
      doubleCounter()
      value
    }
    result shouldBe CounterState(4)
  }

  @Test
  fun stack() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    fun ListBuilder<CounterState>.incrementCounter() {
      val state = last()
      add(state.copy(count = state.count + 1))
    }

    fun ListBuilder<CounterState>.doubleCounter() {
      val state = last()
      add(state.copy(count = state.count * 2))
    }

    val result = runCC {
      buildListLocally {
        add(CounterState(0))
        incrementCounter()
        doubleCounter()
        doubleCounter()
      }
    }
    result shouldBe listOf(CounterState(0), CounterState(1), CounterState(2), CounterState(4))
  }
}