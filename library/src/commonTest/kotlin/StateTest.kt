import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StateTest {
  @Test
  fun simple() = runTest {
    // Usage example
    data class CounterState(val count: Int)

    suspend fun State<CounterState>.incrementCounter() {
      modify { state -> state.copy(count = state.count + 1) }
    }

    suspend fun State<CounterState>.doubleCounter() {
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

    suspend fun StackState<CounterState>.incrementCounter() {
      modifyLast<CounterState> { state -> state.copy(count = state.count + 1) }
    }

    suspend fun StackState<CounterState>.doubleCounter() {
      modifyLast<CounterState> { state -> state.copy(count = state.count * 2) }
    }

    val result = runCC {
      runStackState(CounterState(0)) {
        incrementCounter()
        doubleCounter()
        doubleCounter()
        get<List<CounterState>>()
      }
    }
    result shouldBe listOf(CounterState(0), CounterState(1), CounterState(2), CounterState(4))
  }
}