import arrow.core.raise.Raise
import effekt.HandlerPrompt
import effekt.discard
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

inline fun runTestCC(
  context: CoroutineContext = EmptyCoroutineContext,
  timeout: Duration? = null,
  crossinline testBody: suspend TestScope.() -> Unit
): TestResult = if (timeout == null) runTest(context) {
  runCC { testBody() }
} else runTest(context, timeout) {
  runCC { testBody() }
}

inline fun <Error, R> HandlerPrompt<R>.Raise(crossinline transform: (Error) -> R): Raise<Error> = object : Raise<Error> {
  override fun raise(error: Error): Nothing = discard { transform(error) }
}