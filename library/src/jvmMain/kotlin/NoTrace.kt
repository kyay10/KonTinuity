import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext

/*
 * Inspired by KotlinX Coroutines:
 * https://github.com/Kotlin/kotlinx.coroutines/blob/3788889ddfd2bcfedbff1bbca10ee56039e024a2/kotlinx-coroutines-core/jvm/src/Exceptions.kt#L29
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual abstract class SeekingCoroutineContextException : CancellationException("Should never get swallowed") {
  actual abstract fun use(context: CoroutineContext)
  override fun fillInStackTrace(): Throwable {
    // Prevent Android <= 6.0 bug.
    stackTrace = emptyArray()
    // We don't need stacktrace on shift, it hurts performance.
    return this
  }
}