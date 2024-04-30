import androidx.compose.runtime.*

public class Shift<T, R> internal constructor(private val reset: Reset<R>) {
  @Suppress("UNCHECKED_CAST")
  internal var state: T = null as T

  public suspend operator fun invoke(value: T): R {
    state = value
    return reset.resumeAt(this)
  }
}

@NonRestartableComposable
@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T = remember { Shift<T, R>(this) }.configure(block)

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

// TODO: investigate if we can reuse Recomposer and GatedFrameClock here
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