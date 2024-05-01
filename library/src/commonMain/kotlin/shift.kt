import androidx.compose.runtime.*
import kotlin.coroutines.suspendCoroutine

public fun interface Shift<T, R> {
  public suspend operator fun invoke(value: T): R
}

public class ShiftState<T, R> internal constructor(private val reset: Reset<R>) : Shift<T, R> {
  @Suppress("UNCHECKED_CAST")
  internal var state: T = null as T

  override suspend operator fun invoke(value: T): R {
    state = value
    return reset.resumeAt(this)
  }
}

@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T =
  remember { ShiftState<T, R>(this) }.configure(block)

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

@Composable
public fun <T, R> Reset<R>.reset(
  body: @Composable Reset<T>.() -> T
): T {
  val compositionContext = rememberCompositionContext()
  return await {
    suspendCoroutine {
      val composition = ControlledComposition(UnitApplier, compositionContext)
      val reset = Reset(it, clock, composition, recomposer)
      composition.setContent {
        reset.emitter(reset.runReset(body))
      }
    }
  }
}


@Composable
public fun <T, R> Reset<R>.await(block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}