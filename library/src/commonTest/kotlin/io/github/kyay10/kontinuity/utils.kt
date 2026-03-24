package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
import io.github.kyay10.kontinuity.effekt.HandlerPrompt
import io.github.kyay10.kontinuity.effekt.discard
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest as coroutinesRunTest

private const val useRunSuspend = false

fun runTestCC(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend () -> Unit
) = runTest(context, timeout) { runCC(testBody) }

fun runSuspendCC(testBody: suspend () -> Unit) = runSuspend { runCC(testBody) }

fun runTest(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend () -> Unit
): TestResult {
  val block = if (useRunSuspend) ({ runSuspend { testBody() } }) else testBody
  return if (timeout == null) coroutinesRunTest(context) { block() }
  else coroutinesRunTest(context, timeout) { block() }
}

inline fun <Error, R> HandlerPrompt<R>.Raise(crossinline transform: (Error) -> R): Raise<Error> =
  object : Raise<Error> {
    override fun raise(r: Error): Nothing = discard { transform(r) }
  }

expect fun runSuspend(block: suspend () -> Unit)

inline fun repeatIteratorless(
  times: Int,
  block: (Int) -> Unit
) {
  var i = 0
  while (i < times) {
    block(i)
    i++
  }
}

fun <T> flowOfWithDelay(vararg elements: T) = flowOf(*elements).onEach {
  delay(0.milliseconds)
}

suspend fun <R> assertNonTerminatingCC(timeout: Duration = 10.milliseconds, block: suspend () -> R) =
  nonTerminatingCC(timeout, block) shouldBe null

suspend fun <R> nonTerminatingCC(timeout: Duration = 10.milliseconds, block: suspend () -> R) =
  withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeoutOrNull(timeout) { runCC(block) }
  }