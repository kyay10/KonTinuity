@file:Suppress("SUBTYPING_BETWEEN_CONTEXT_RECEIVERS")

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

data class ResumableError<Error, T, R>(val error: Error, val continuation: SubCont<T, R>)

context(Prompt<ResumableError<Error, T, R>>, Prompt<R>)
@ResetDsl
suspend fun <Error, T, R> resetWithHandler(
  handler: (Error, SubCont<T, R>) -> R, body: suspend () -> R
): R = reset<R> {
  val (error, continuation) = reset<ResumableError<Error, T, R>> {
    abort<R>(body())
  }
  handler(error, continuation)
}

context(Prompt<ResumableError<Error, T, R>>, Prompt<R>)
@ResetDsl
suspend fun <Error, T, R> fcontrol(error: Error): T = peekSubCont<_, R>(deleteDelimiter = false) { sk ->
  abort<ResumableError<Error, T, R>>(ResumableError(error, sk))
}

class FcontrolJvmTest {
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