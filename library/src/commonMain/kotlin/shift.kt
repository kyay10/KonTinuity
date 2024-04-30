import androidx.compose.runtime.*

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

@NonRestartableComposable
@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T =
  remember { ShiftState<T, R>(this) }.configure(block)

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

// TODO: investigate if we can reuse Recomposer here
@Composable
public fun <T, R> Reset<R>.reset(
  body: @Composable Reset<T>.() -> T
): T = await { resetAliased(body) }

private suspend fun <R> resetAliased(
  body: @Composable Reset<R>.() -> R
): R = reset(body)

@Composable
public inline fun <T, R> Reset<R>.await(crossinline block: suspend () -> T): T = shift { continuation ->
  continuation(block())
}