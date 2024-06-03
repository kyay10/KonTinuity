import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FcontrolTest {
  @Test
  fun fcontrol() = runTest {
    runCC {
      suspend fun product(s: Iterator<Int>): Int = with(Handle<Int, Nothing, Int>()) {
        resetWithHandler({ error, _ -> error }) {
          var acc = 1
          for (i in s) {
            if (i == 0) fcontrol(0)
            acc *= i
          }
          acc
        }
      }
      // Infinite sequence
      product(generateSequence(5) { it - 1 }.iterator()) shouldBe 0
    }
  }
}