@file:Suppress("SUBTYPING_BETWEEN_CONTEXT_RECEIVERS")

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FcontrolTest {
  @Test
  fun fcontrol() = runTest {
    runCC {
      with(Prompt<Int>(), Prompt<ResumableError<Int, Nothing, Int>>()) {
        suspend fun product(s: Iterator<Int>): Int = resetWithHandler({ error, _ -> error }) {
          var acc = 1
          for (i in s) {
            if (i == 0) fcontrol(0)
            acc *= i
          }
          acc
        }
        // Infinite sequence
        product(generateSequence(5) { it - 1 }.iterator()) shouldBe 0
      }
    }
  }
}

private inline fun <A, B, R> with(a: A, b: B, block: context(A, B) () -> R): R = block(a, b)