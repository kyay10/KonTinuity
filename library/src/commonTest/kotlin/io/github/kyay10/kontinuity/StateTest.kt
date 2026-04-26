package io.github.kyay10.kontinuity

import kotlin.test.Test

private interface MyState<T> {
  var value: T
}

private suspend fun <T, R> runMyState(value: T, block: suspend MyState<T>.() -> R): R =
  runState(value) {
    block(
      object : MyState<T> {
        override var value by this@runState::value
      }
    )
  }

private suspend fun <T> runStackState(value: T, block: suspend MyState<T>.() -> Unit): List<T> = buildListLocally {
  add(value)
  block(
    object : MyState<T> {
      override var value: T
        get() = last()
        set(value) {
          add(value)
        }
    }
  )
}

class StateTest {
  private data class CounterState(val count: Int)

  private fun MyState<CounterState>.incrementCounter() {
    value = value.copy(count = value.count + 1)
  }

  private fun MyState<CounterState>.doubleCounter() {
    value = value.copy(count = value.count * 2)
  }

  @Test
  fun simple() = runTestCC {
    runMyState(CounterState(0)) {
      incrementCounter()
      doubleCounter()
      doubleCounter()
      value
    } shouldEq CounterState(4)
  }

  @Test
  fun stack() = runTestCC {
    runStackState(CounterState(0)) {
      incrementCounter()
      doubleCounter()
      doubleCounter()
    } shouldEq listOf(CounterState(0), CounterState(1), CounterState(2), CounterState(4))
  }
}
