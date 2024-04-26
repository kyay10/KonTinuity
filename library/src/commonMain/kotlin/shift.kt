import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

public class Shift<T, R> internal constructor(private val reset: Reset<R>) : RememberObserver {
  private val output = Channel<R>(1)

  @Suppress("UNCHECKED_CAST")
  private var state: T = null as T

  private var job: Job? = null

  public suspend operator fun invoke(value: T): R = with(reset) {
    state = value
    recomposeScope.invalidate()
    reachedResumePoint = false
    currentOutput = output
    clock.isRunning = true
    return output.receive()
  }

  internal fun configure(producer: suspend Shift<T, R>.() -> R): T = with(reset) {
    if (reachedResumePoint) {
      val previousOutputBuffer = currentOutput
      job?.cancel()
      job = coroutineScope.launch {
        suspensions.receive()
        previousOutputBuffer.send(producer())
      }
      raise.raise(Unit)
    }
    if (currentOutput == output) reachedResumePoint = true
    state
  }

  override fun onAbandoned() {
    job?.cancel()
  }

  override fun onForgotten() {
    job?.cancel()
  }

  override fun onRemembered() {
  }
}

@NonRestartableComposable
@Composable
public fun <T, R> Reset<R>.shift(block: suspend (Shift<T, R>) -> R): T =
  remember { Shift<T, R>(this) }.configure(block)

@Composable
public fun <R> Reset<R>.shiftWith(value: R): Nothing =
  shift { value }

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