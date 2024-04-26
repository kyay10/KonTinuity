import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal data class EffectState<R> @PublishedApi internal constructor(var value: R = null as R)

// TODO: marking this with @DisallowComposableCalls causes some crashes when early-returning. Might be Compose bug.
@Composable
@NonRestartableComposable
public inline fun <R> Reset<*>.effect(block: () -> R): R = with(remember { EffectState<R>() }) {
  if (reachedResumePoint) value = block()
  value
}