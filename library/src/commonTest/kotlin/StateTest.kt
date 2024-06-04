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
        buildList {
          this@runState.forEach { add(it) }
        }
      }
    }
    result shouldBe listOf(CounterState(4), CounterState(2), CounterState(1), CounterState(0))
  }
}