package io.github.kyay10.kontinuity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.throwable.shouldHaveMessage
import kotlin.test.Test

class ErrorTest {
  @Test
  fun `multishot nested resumptions fail`() = runTest {
    shouldThrow<IllegalStateException> {
      runCC {
        newReset {
          shiftOnce { resume ->
            resume locally {
              resume(Unit)
            }
          }
        }
      }
    } shouldHaveMessage SEGMENT_ALREADY_USED
  }

  @Test
  fun `multishot fails`() = runTest {
    shouldThrow<IllegalStateException> {
      runCC {
        newReset {
          shiftOnce { resume ->
            resume(Unit)
            resume(Unit)
          }
        }
      }
    } shouldHaveMessage SEGMENT_ALREADY_USED
  }
}