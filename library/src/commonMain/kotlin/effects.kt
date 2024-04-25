import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@PublishedApi
internal data class EffectState<R> @PublishedApi internal constructor(var value: Maybe<R> = nothing())

context(Reset<*>)
@Composable
public inline fun <R> effect(block: @ComprehensionDsl () -> R): R {
  val state = remember { EffectState<R>() }
  if (shouldUpdateEffects) state.value = nothing()
  return state.value.getOrElse(block).also { state.value = just(it) }
}