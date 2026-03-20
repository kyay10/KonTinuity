package io.github.kyay10.kontinuity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.throwable.shouldHaveMessage
import kotlin.test.Test

class ErrorMultishotTest {
  @Test
  fun `nested resumptions fail`() = runTest {
    shouldThrow<IllegalStateException> {
      runCC {
        newReset {
          shift { resume ->
            resume locally {
              resume(Unit)
            }
          }
        }
      }
    } shouldHaveMessage REENTRANT_NOT_SUPPORTED
  }
}