package io.github.kyay10.kontinuity

import kotlin.test.Test

class AmbTest {
  @Test
  fun example() = runTestCC {
    val printed = StringBuilder()
    ambList {
      printed.appendLine("Trying to flip a coin...")
      if (!flip()) {
        printed.appendLine("We dropped the coin")
        -1
      } else {
        printed.appendLine("We caught the coin")
        if (flip()) {
          printed.appendLine("That's heads")
          0
        } else {
          printed.appendLine("That's tails")
          1
        }
      }
    } shouldEq listOf(0, 1, -1)
    printed.toString() shouldEq
      """
      |Trying to flip a coin...
      |We caught the coin
      |That's heads
      |That's tails
      |We dropped the coin
      |"""
        .trimMargin()
  }
}
