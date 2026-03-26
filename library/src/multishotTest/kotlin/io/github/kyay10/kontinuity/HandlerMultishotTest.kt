package io.github.kyay10.kontinuity

import arrow.core.Some
import kotlin.test.Test

class HandlerMultishotTest {
  @Test
  fun ex5dot3dot4() = runTestCC {
    nondet { stringInput("123") { number() } } shouldEq listOf(123, 12, 1)
    backtrack { stringInput("123") { number() } } shouldEq Some(123)
  }
}

context(_: Exc)
suspend fun <R> stringInput(input: String, block: suspend Input.() -> R): R = runState(0) {
  block {
    ensure(value < input.length)
    input[value++]
  }
}