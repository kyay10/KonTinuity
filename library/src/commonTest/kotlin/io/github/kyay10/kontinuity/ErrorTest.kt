package io.github.kyay10.kontinuity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.throwable.shouldHaveMessage
import kotlin.test.Test

class ErrorTest {
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
    } shouldHaveMessage if (SUPPORTS_MULTISHOT) REENTRANT_NOT_SUPPORTED else COPYING_NOT_SUPPORTED
  }

  @Test
  fun `multishot fails`() = runTest {
    if (SUPPORTS_MULTISHOT) return@runTest
    shouldThrow<IllegalStateException> {
      runCC {
        newReset {
          shift { resume ->
            resume(Unit)
            resume(Unit)
          }
        }
      }
    } shouldHaveMessage COPYING_NOT_SUPPORTED
  }
}