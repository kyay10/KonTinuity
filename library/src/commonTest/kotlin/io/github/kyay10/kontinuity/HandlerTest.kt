package io.github.kyay10.kontinuity

import kotlin.test.Test

class HandlerTest {
  @Test
  fun ex5dot3dot5() = runTestCC {
    buildList { for (i in effectfulIterable { numbers(10) }) add(i) } shouldEq (0..10).toList()
  }
}

context(_: Generator<Int>)
suspend fun numbers(upto: Int) = repeatIteratorless(upto + 1) { yield(it) }
