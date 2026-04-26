package io.github.kyay10.kontinuity.higherorder

import io.github.kyay10.kontinuity.ambList
import io.github.kyay10.kontinuity.runTestCC
import io.github.kyay10.kontinuity.shouldEq
import kotlin.test.Test

class WriterMultishotTest {
  @Test
  fun withNonDetTest() = runTestCC {
    runWriter(0, Int::plus) {
      ambList {
        listen {
          tell(1)
          if (flip()) {
            tell(2)
            true
          } else {
            tell(3)
            false
          }
        }
      }
    } shouldEq (6 to listOf(3 to true, 4 to false))
  }
}
