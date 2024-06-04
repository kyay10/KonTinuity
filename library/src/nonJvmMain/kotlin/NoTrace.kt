import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual abstract class SeekingCoroutineContextException: CancellationException("Should never get swallowed") {
  actual abstract fun use(context: CoroutineContext)
}