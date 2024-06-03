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

  // https://www.brinckerhoff.org/clements/csc530-sp08/Readings/kiselyov-2006.pdf
  @Test
  @Suppress("UNCHECKED_CAST")
  fun example6FromDBDCPaper() = runTest {
    runCC {
      val p = Reader<Int>()
      val r = Reader<Int>()
      val f = p.pushReader<_, Any?>(1) {
        newReset {
          r.pushReader(10) {
            shift<Int, _> {
              it
            } shouldBe 0
            p.get() + r.get()
          }
        }
      } as suspend (Int) -> Int
      p.pushReader(2) {
        r.pushReader(20) {
          f(0)
        }
      }
    } shouldBe 12
  }
}