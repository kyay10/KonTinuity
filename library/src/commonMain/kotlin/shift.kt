import androidx.compose.runtime.*
import kotlin.coroutines.suspendCoroutine

public typealias Cont<T, R> = suspend (T) -> R

public class ControlOrShiftCont<T, R> @PublishedApi internal constructor(
  private val state: EffectState<T>, private val reset: Reset<*>, private val target: Reset<R>
) {
  public val shift: Cont<T, R> = {
    with(reset) {
      state.resume(it, target, true)
    }
  }
  public val control: Cont<T, R> = {
    with(reset) {
      state.resume(it, target, false)
    }
  }
}

@PublishedApi
@Composable
internal inline fun <T, R> Reset<R>.gimmeAllControl(crossinline block: suspend (EffectState<T>) -> R): T = with(currentReset){
  remember { EffectState<T>() }.configure(currentRecomposeScope, this@gimmeAllControl) @DontMemoize { block(this) }
}

// TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization
@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.shift(crossinline block: suspend (Cont<T, R>) -> R): T =
  controlOrShift { block(it.shift) }

@Suppress("UNCHECKED_CAST")
@Composable
@ResetDsl
public inline fun <T, R1, R2> Reset<R1>.shiftAndChangeType(
  crossinline block: suspend (Cont<T, R2>) -> R1, crossinline rest: @Composable Reset<R2>.(T) -> R2
): Nothing {
  val originalReset: Reset<R1> = this
  val castedReset: Reset<R2> = this as Reset<R2>
  castedReset.runReset { rest(originalReset.shift { k -> block(k as Cont<T, R2>) }) }
  throw Suspended(this@shiftAndChangeType)
}

@Suppress("UNCHECKED_CAST")
@Composable
@ResetDsl
public inline fun <T, R1> Reset<R1>.controlAndChangeType(
  crossinline block: suspend (Cont<T, Nothing>) -> R1, crossinline rest: @Composable (T) -> Nothing
): Nothing = rest(control { k -> block(k as Cont<T, Nothing>) })

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.control(crossinline block: suspend (Cont<T, R>) -> R): T =
  controlOrShift { block(it.control) }

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.controlOrShift(crossinline block: suspend (ControlOrShiftCont<T, R>) -> R): T {
  val reset = currentReset
  return gimmeAllControl {
    try {
      block(ControlOrShiftCont(it, reset, this@controlOrShift))
    } catch (e: Suspended) {
      if (e.token === it) {
        suspendCoroutine<Nothing> { }
      } else throw e
    }
  }
}

@Composable
@ResetDsl
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.await(crossinline block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}