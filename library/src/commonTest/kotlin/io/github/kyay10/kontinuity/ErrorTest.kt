package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.internal.SEGMENT_ALREADY_USED
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.throwable.shouldHaveMessage
import kotlin.test.Test

class ErrorTest {
  @Test
  fun `multishot nested resumptions fail`() = runTest {
    shouldThrow<IllegalStateException> {
      runCC { handle { useOnce { resume -> resume locally { resume(Unit) } } } }
    } shouldHaveMessage SEGMENT_ALREADY_USED
  }

  @Test
  fun `multishot fails`() = runTest {
    shouldThrow<IllegalStateException> {
      runCC<Unit> {
        handle {
          useOnce { resume ->
            resume(Unit)
            resume(Unit)
          }
        }
      }
    } shouldHaveMessage SEGMENT_ALREADY_USED
  }
}
