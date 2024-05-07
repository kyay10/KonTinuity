import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FcontrolTest {
  @Test
  fun fcontrol() = runTest {
    suspend fun product(s: Iterator<Int>): Int = resetWithHandler<Int, Nothing, Int>({
      var acc = 1
      for (i in s) {
        @Suppress("IMPLICIT_NOTHING_TYPE_ARGUMENT_IN_RETURN_POSITION")
        if (i == 0) fcontrol(0)
        acc *= i
      }
      acc
    }, { error, _ -> error })
    // Infinite sequence
    product(generateSequence(5) { it - 1 }.iterator()) shouldBe 0
  }
}