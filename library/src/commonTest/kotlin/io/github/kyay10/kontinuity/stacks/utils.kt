package io.github.kyay10.kontinuity.stacks

import io.kotest.matchers.equals.beEqual
import io.kotest.matchers.should
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest as coroutinesRunTest

fun runTestCC(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend context(Locality) () -> Unit,
) = runTest(context, timeout) { runCC(testBody) }

fun runTest(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend () -> Unit,
): TestResult =
  if (timeout == null) coroutinesRunTest(context) { testBody() } else coroutinesRunTest(context, timeout) { testBody() }

@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@IgnorableReturnValue
infix fun <@kotlin.internal.OnlyInputTypes T> T.shouldEq(expected: T) {
  this should beEqual(expected)
}
