import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@PublishedApi
internal class EffectState<R> {
  @Suppress("UNCHECKED_CAST")
  var value: R = null as R
}

// TODO: marking this with @DisallowComposableCalls causes some crashes when early-returning. Might be Compose bug.
@Composable
@ResetDsl
public inline fun <R> Reset<*>.effect(block: () -> R): R = with(remember { EffectState<R>() }) {
  if (reachedResumePoint) value = block()
  value
}