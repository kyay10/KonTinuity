import androidx.compose.runtime.*

public fun interface Shift<T, R> {
  public suspend operator fun invoke(value: T): R
}

public class ShiftState<T, R>(private val reset: Reset<R>) : Shift<T, R> {
  @Suppress("UNCHECKED_CAST")
  internal var state: T = null as T

  override suspend operator fun invoke(value: T): R {
    state = value
    return reset.resumeAt(this)
  }
}

// TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization
@Composable
public inline fun <T, R> Reset<R>.shift(crossinline block: suspend (Shift<T, R>) -> R): T =
  remember { ShiftState<T, R>(this) }.configure(currentRecomposeScope) @DontMemoize {
    block(it)
  }

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

@Composable
public inline fun <T, R> Reset<R>.await(crossinline block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}