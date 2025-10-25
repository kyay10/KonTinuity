package io.github.kyay10.kontinuity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.throwable.shouldHaveMessage
import kotlin.test.Test

class ErrorTest {
  @Test
  fun `nested resumptions fail`() = runTestCC {
    shouldThrow<IllegalStateException> {
      newReset {
        shift { resume ->
          resume locally {
            resume(Unit)
          }
        }
      }
    } shouldHaveMessage "Reentrant resumptions are not supported"
  }
}