import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

public data class EffectState internal constructor(@PublishedApi internal var value: Maybe<Any?>)

context(Reset<*>)
public val effect: EffectState
  @Composable get() = remember(this@Reset) { EffectState(nothing()) }

context(Reset<*>)
@Suppress("UNCHECKED_CAST")
public inline operator fun <R> EffectState.invoke(block: @ComprehensionDsl () -> R): R {
  if (shouldUpdateEffects) value = nothing()
  return value.fold(block) { it as R }.also { value = just(it) }
}