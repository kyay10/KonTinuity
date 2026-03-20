package io.github.kyay10.kontinuity.effekt.higherorder

import io.github.kyay10.kontinuity.effekt.collect
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WriterMultishotTest {
  @Test
  fun withNonDetTest() = runTestCC {
    runWriter(0, Int::plus) {
      collect {
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
    } shouldBe (6 to listOf(3 to true, 4 to false))
  }
}