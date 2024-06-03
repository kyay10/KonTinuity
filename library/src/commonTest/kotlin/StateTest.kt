import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StateTest {
  @Test
  fun stateMonad() = runTest {
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
}