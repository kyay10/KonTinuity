import arrow.core.raise.Raise
import effekt.HandlerPrompt
import effekt.discard
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest as coroutinesRunTest

private const val useRunSuspend = false

fun runTestCC(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend TestScope.() -> Unit
) = runTest(context, timeout) { runCC { testBody() } }

fun runTest(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  testBody: suspend TestScope.() -> Unit
): TestResult {
  val block = if (useRunSuspend) ({ runSuspend { testBody() } }) else testBody
  return if (timeout == null) coroutinesRunTest(context, testBody = block)
  else coroutinesRunTest(context, timeout, block)
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

inline fun IntRange.forEachIteratorless(block: (Int) -> Unit) {
  var index = start
  while (index <= endInclusive) {
    block(index)
    index++
  }
}